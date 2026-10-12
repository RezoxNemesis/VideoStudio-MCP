"""Bounded mounted copies with symlink-safe, create-only atomic publication."""
from __future__ import annotations
import ctypes
import errno
import hashlib
import os
from pathlib import Path
import stat

from .archive import MAX_ARCHIVE, checksum


class ExchangeUnavailable(RuntimeError):
    def __init__(self, code, partial, sha, size):
        super().__init__(code)
        self.receipt = {"state": "waiting_storage", "code": code, "partialPath": str(partial),
            "sha256": sha, "bytes": size, "published": False, "remoteDrivePersistenceVerified": False}


def _parent(path, *, create=False):
    """Walk directory FDs with NOFOLLOW, including ancestors subject to races."""
    path = Path(os.path.abspath(Path(path).expanduser()))
    descriptor = os.open("/", os.O_RDONLY | os.O_DIRECTORY)
    try:
        for component in path.parts[1:-1]:
            if create:
                try:
                    os.mkdir(component, mode=0o700, dir_fd=descriptor)
                except FileExistsError:
                    pass
            child = os.open(component, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=descriptor)
            os.close(descriptor); descriptor = child
        return path, descriptor, path.name
    except BaseException:
        os.close(descriptor); raise


def _read(parent, name, *, limit=MAX_ARCHIVE, expected_size=None):
    descriptor = os.open(name, os.O_RDONLY | os.O_NOFOLLOW, dir_fd=parent)
    with os.fdopen(descriptor, "rb") as stream:
        before = os.fstat(stream.fileno())
        if not stat.S_ISREG(before.st_mode) or not 1 <= before.st_size <= limit or (expected_size is not None and before.st_size != expected_size):
            raise ValueError("MOUNT_ARTIFACT_SIZE_OR_TYPE_INVALID")
        value, count = hashlib.sha256(), 0
        while block := stream.read(min(128 * 1024, before.st_size - count + 1)):
            count += len(block)
            if count > before.st_size:
                raise ValueError("MOUNT_SOURCE_CHANGED_DURING_COPY")
            value.update(block)
        after = os.fstat(stream.fileno())
        if count != before.st_size or (before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns, before.st_ctime_ns) != (
                after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns, after.st_ctime_ns):
            raise ValueError("MOUNT_SOURCE_CHANGED_DURING_COPY")
        return value.hexdigest(), count


def _rename_noreplace(parent, source_name, target_name):
    """Linux atomic rename works on many FUSE mounts that reject hard links."""
    libc = ctypes.CDLL(None, use_errno=True)
    rename = getattr(libc, "renameat2", None)
    if rename is None:
        raise OSError(errno.ENOSYS, "Atomic no-replace rename is unavailable")
    rename.argtypes = (ctypes.c_int, ctypes.c_char_p, ctypes.c_int, ctypes.c_char_p, ctypes.c_uint)
    rename.restype = ctypes.c_int
    if rename(parent, os.fsencode(source_name), parent, os.fsencode(target_name), 1) != 0:
        code = ctypes.get_errno()
        raise OSError(code, os.strerror(code))


def _promote(parent, temporary, target):
    try:
        os.link(temporary, target, src_dir_fd=parent, dst_dir_fd=parent, follow_symlinks=False)
        return "hard_link"
    except FileExistsError:
        return "existing"
    except OSError:
        try:
            _rename_noreplace(parent, temporary, target)
            return "rename_noreplace"
        except FileExistsError:
            return "existing"


def _flush_directory(descriptor):
    try:
        os.fsync(descriptor)
        return True
    except OSError as error:
        if error.errno in (errno.EINVAL, errno.ENOTSUP, errno.EBADF):
            # Some FUSE providers do not expose directory fsync. Byte readback is
            # still measured, and remote persistence is explicitly unverified.
            return False
        raise


def copy_verified(source, target, *, expected_sha=None, limit=MAX_ARCHIVE):
    """Return a publication receipt only after two bounded convergent hash reads.

    If neither atomic primitive works, keep the verified partial and return its
    location through ExchangeUnavailable; the flat native result never appears.
    """
    if expected_sha is not None:
        checksum(expected_sha)
    source_path, source_parent, source_name = _parent(source)
    target_parent = None
    temporary = None
    preserve = False
    try:
        target_path, target_parent, target_name = _parent(target, create=True)
        source_sha, size = _read(source_parent, source_name, limit=limit)
        if expected_sha is not None and source_sha != expected_sha:
            raise ValueError("MOUNT_SOURCE_SHA256_MISMATCH")
        try:
            existing_sha, existing_size = _read(target_parent, target_name, limit=limit)
        except FileNotFoundError:
            pass
        else:
            if existing_sha != source_sha or existing_size != size:
                raise ValueError("MOUNT_OUTPUT_CONFLICT")
            return {"state": "published", "published": True, "path": str(target_path), "bytes": size,
                "sha256": source_sha, "readbackSha256": existing_sha, "publication": "existing_verified",
                "remoteDrivePersistenceVerified": False}
        temporary = ".exchange_%s_%s.partial" % (target_path.stem[:96], source_sha)
        try:
            staged_sha, staged_size = _read(target_parent, temporary, limit=limit)
        except FileNotFoundError:
            input_fd = os.open(source_name, os.O_RDONLY | os.O_NOFOLLOW, dir_fd=source_parent)
            try:
                output_fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600, dir_fd=target_parent)
            except BaseException:
                os.close(input_fd)
                preserve = True  # A concurrent writer owns any existing partial.
                raise
            value, count = hashlib.sha256(), 0
            with os.fdopen(input_fd, "rb") as stream, os.fdopen(output_fd, "wb") as output:
                while block := stream.read(min(128 * 1024, size - count + 1)):
                    count += len(block)
                    if count > size:
                        raise ValueError("MOUNT_SOURCE_CHANGED_DURING_COPY")
                    output.write(block); value.update(block)
                output.flush(); os.fsync(output.fileno())
            if count != size or value.hexdigest() != source_sha:
                raise ValueError("MOUNT_SOURCE_CHANGED_DURING_COPY")
        else:
            preserve = True
            if staged_sha != source_sha or staged_size != size:
                raise ValueError("MOUNT_STAGING_CONFLICT")
        staged_sha, _ = _read(target_parent, temporary, limit=limit, expected_size=size)
        again_sha, again_size = _read(source_parent, source_name, limit=limit)
        if staged_sha != source_sha or again_sha != source_sha or again_size != size:
            raise ValueError("MOUNT_COPY_READBACK_MISMATCH")
        try:
            promotion = _promote(target_parent, temporary, target_name)
        except OSError as error:
            preserve = True
            code = "MOUNT_ATOMIC_PROMOTION_UNAVAILABLE" if error.errno in (errno.ENOSYS, errno.EINVAL, errno.ENOTSUP, errno.EPERM, errno.EXDEV) else "MOUNT_ATOMIC_PROMOTION_FAILED"
            raise ExchangeUnavailable(code, target_path.parent / temporary, source_sha, size) from error
        final_sha, _ = _read(target_parent, target_name, limit=limit, expected_size=size)
        if final_sha != source_sha:
            raise ValueError("MOUNT_OUTPUT_CONFLICT" if promotion == "existing" else "MOUNT_PUBLICATION_READBACK_MISMATCH")
        preserve = False
        flushed = _flush_directory(target_parent)
        return {"state": "published", "published": True, "path": str(target_path), "bytes": size,
            "sha256": source_sha, "readbackSha256": final_sha, "publication": promotion,
            "directoryFsyncSupported": flushed, "remoteDrivePersistenceVerified": False}
    finally:
        if temporary is not None and target_parent is not None and not preserve:
            try:
                os.unlink(temporary, dir_fd=target_parent)
            except FileNotFoundError:
                pass
        os.close(source_parent)
        if target_parent is not None:
            os.close(target_parent)
