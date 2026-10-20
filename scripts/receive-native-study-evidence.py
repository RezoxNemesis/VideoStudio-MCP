"""Transport an explicitly pinned native study; never execute or generate artwork.

The private original and reference comparisons must stay outside this bundle.
An exact source revision and every file digest are checked before uploading the
opaque ZIP. This proves transport integrity, not visual quality or CI rendering.
"""
import base64
import hashlib
import io
import json
import os
import pathlib
import re
import subprocess
import zipfile


def validate(data, expected_source):
    if len(data) > 48 * 1024 * 1024:
        raise ValueError("Study ZIP exceeds transport bound")
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        entries = archive.infolist()
        names = [item.filename for item in entries]
        if len(entries) > 512 or len(set(names)) != len(names):
            raise ValueError("Repeated or excessive study files")
        if sum(item.file_size for item in entries) > 160 * 1024 * 1024:
            raise ValueError("Study expands beyond transport bound")
        for item in entries:
            name = pathlib.PurePosixPath(item.filename)
            if (name.is_absolute() or ".." in name.parts or "\\" in item.filename
                    or item.is_dir() or item.file_size > 32 * 1024 * 1024
                    or (item.external_attr >> 16) & 0o170000 == 0o120000):
                raise ValueError("Unsafe study entry")
        manifest = json.loads(archive.read("delivery-manifest.json"))
        if (manifest["schema"] != "videostudio.native-study-delivery.v1"
                or manifest["sourceCommit"] != expected_source
                or manifest["referencePixelsIncluded"] is not False
                or manifest["renderLocation"] != "Android native Canvas under Robolectric"):
            raise ValueError("Study source or rendering declaration differs")
        declared = manifest["files"]
        if set(declared) != set(names) - {"delivery-manifest.json"}:
            raise ValueError("Study inventory differs")
        for name, digest in declared.items():
            if hashlib.sha256(archive.read(name)).hexdigest() != digest:
                raise ValueError("Study file SHA256 differs: " + name)
        return manifest


def main():
    blob = os.environ["NATIVE_STUDY_BLOB_SHA"]
    expected = os.environ["NATIVE_STUDY_SHA256"]
    if not re.fullmatch(r"[0-9a-f]{40}", blob) or not re.fullmatch(r"[0-9a-f]{64}", expected):
        raise ValueError("Exact blob SHA and ZIP SHA256 required")
    repository = os.environ["GITHUB_REPOSITORY"]
    raw = subprocess.check_output(["gh", "api", f"repos/{repository}/git/blobs/{blob}"])
    response = json.loads(raw)
    if response.get("encoding") != "base64" or response.get("size", 0) > 48 * 1024 * 1024:
        raise ValueError("Unsupported evidence blob")
    data = base64.b64decode(response["content"])
    if hashlib.sha256(data).hexdigest() != expected:
        raise ValueError("Native evidence ZIP SHA256 differs")
    manifest = validate(data, os.environ["GITHUB_SHA"])
    root = pathlib.Path("work/native-study-evidence")
    root.mkdir(parents=True)
    (root / "native-authored-study.zip").write_bytes(data)
    (root / "native-authored-study.zip.sha256").write_text(expected + "  native-authored-study.zip\n")
    (root / "delivery-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print("Verified source-bound native evidence transport; artwork acceptance remains as recorded.")


if __name__ == "__main__":
    main()
