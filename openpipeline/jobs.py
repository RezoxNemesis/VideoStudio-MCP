"""Durable jobs, compare-and-set stages, managed process groups and publication."""
from __future__ import annotations
import fcntl
import hashlib
import os
from pathlib import Path
import shutil
import signal
import sqlite3
import subprocess
import threading
import time
import uuid

from .archive import (MAX_ARCHIVE, RESULT_SCHEMA, atomic_json, checksum, digest, encode,
                      fsync_directory, identifier, owned, package, read_json, unpack_job)
from .config import admission, load

TERMINAL = {"completed", "failed", "cancelled", "expired"}
WAITING = {"queued", "checkpointed", "waiting_compute", "waiting_model"}


def _connect(root):
    connection = sqlite3.connect(Path(root) / "jobs.sqlite3", timeout=10)
    connection.row_factory = sqlite3.Row
    connection.execute("PRAGMA busy_timeout=10000")
    return connection


def update(root, job_id, **values):
    permitted = {"state", "stage", "code", "heartbeat", "updated", "pid", "start_ticks", "result_sha", "result_bytes", "progress"}
    if not values or not values.keys() <= permitted:
        raise ValueError("Invalid internal job update")
    values["updated"] = time.time()
    with _connect(root) as connection:
        connection.execute("UPDATE jobs SET " + ",".join(key + "=?" for key in values) +
                           " WHERE job_id=? AND state NOT IN ('completed','cancelled','expired','failed')",
                           [*values.values(), job_id])


def _start_ticks(pid):
    try:
        # The comm field can contain spaces or parentheses; split after its last ).
        return Path("/proc/%d/stat" % pid).read_text().rsplit(")", 1)[1].split()[19]
    except (OSError, IndexError):
        return None


def _alive(row):
    return bool(row["pid"] and row["start_ticks"] is not None and _start_ticks(row["pid"]) == row["start_ticks"])


def _kill_group(row):
    if not _alive(row):
        return
    try:
        os.killpg(row["pid"], signal.SIGTERM)
    except ProcessLookupError:
        return
    deadline = time.monotonic() + 2
    while _alive(row) and time.monotonic() < deadline:
        time.sleep(.05)
    # Children may outlive their leader after TERM. The group was authenticated
    # above and retains its ID while any original member remains.
    try:
        os.killpg(row["pid"], signal.SIGKILL)
    except ProcessLookupError:
        pass


class Store:
    def __init__(self, config):
        self.config, self.root = config, Path(config["root"])
        self.root.mkdir(parents=True, exist_ok=True, mode=0o700)
        for name in ("jobs", "incoming"):
            (self.root / name).mkdir(exist_ok=True, mode=0o700)
        with _connect(self.root) as connection:
            connection.execute("PRAGMA journal_mode=WAL")
            connection.execute("PRAGMA synchronous=FULL")
            connection.execute("""CREATE TABLE IF NOT EXISTS jobs (
                job_id TEXT PRIMARY KEY, request_id TEXT NOT NULL UNIQUE, request_sha TEXT NOT NULL,
                archive_sha TEXT NOT NULL, state TEXT NOT NULL, stage TEXT NOT NULL,
                code TEXT, created REAL NOT NULL, updated REAL NOT NULL, heartbeat REAL NOT NULL,
                deadline REAL NOT NULL, pid INTEGER, start_ticks TEXT, result_sha TEXT,
                result_bytes INTEGER, progress TEXT)""")

    def row(self, job_id):
        identifier(job_id)
        with _connect(self.root) as connection:
            row = connection.execute("SELECT * FROM jobs WHERE job_id=?", (job_id,)).fetchone()
        if row is None:
            raise KeyError("Unknown job")
        return dict(row)

    def status(self, job_id):
        row = self.row(job_id)
        receipt = {"jobId": row["job_id"], "requestId": row["request_id"], "requestSha256": row["request_sha"],
            "state": row["state"], "stage": row["stage"], "code": row["code"],
            "createdAt": int(row["created"] * 1000), "updatedAt": int(row["updated"] * 1000),
            "heartbeatAt": int(row["heartbeat"] * 1000), "deadlineAt": int(row["deadline"] * 1000), "nativeRendered": False}
        if row["result_sha"]:
            receipt.update(resultSha256=row["result_sha"], resultBytes=row["result_bytes"])
        if row["progress"]:
            import json
            receipt["progress"] = json.loads(row["progress"])
        mount_receipt = self.root / "jobs" / job_id / "mount-exchange.json"
        if mount_receipt.is_file():
            receipt["mountedExchange"] = read_json(mount_receipt)
        return receipt

    def submit(self, archive, *, archive_sha=None, idempotency_key=None):
        archive = Path(archive)
        if archive.is_symlink() or not archive.is_file() or not 1 <= archive.stat().st_size <= MAX_ARCHIVE:
            raise ValueError("INVALID_ARCHIVE_SIZE")
        actual = digest(archive)
        if archive_sha is not None and checksum(archive_sha) != actual:
            raise ValueError("ARCHIVE_SHA256_MISMATCH")
        stage = self.root / "incoming" / uuid.uuid4().hex
        request, request_sha = unpack_job(archive, stage)
        try:
            if idempotency_key is not None and identifier(idempotency_key) != request["requestId"]:
                raise ValueError("IDEMPOTENCY_KEY_MISMATCH")
            with _connect(self.root) as connection:
                connection.execute("BEGIN IMMEDIATE")
                previous = connection.execute("SELECT * FROM jobs WHERE request_id=?", (request["requestId"],)).fetchone()
                if previous:
                    if previous["request_sha"] != request_sha:
                        raise ValueError("IDEMPOTENCY_CONFLICT")
                    job_id = previous["job_id"]
                else:
                    count = connection.execute("SELECT count(*) FROM jobs WHERE state NOT IN ('completed','failed','cancelled','expired')").fetchone()[0]
                    if count >= self.config["queueLimit"]:
                        raise ValueError("QUEUE_CAPACITY_REACHED")
                    if shutil.disk_usage(self.root).free < 1024**3:
                        raise ValueError("INSUFFICIENT_DISK_SPACE")
                    job_id, now = uuid.uuid4().hex, time.time()
                    folder = self.root / "jobs" / job_id
                    folder.mkdir(mode=0o700)
                    os.rename(stage, folder / "input")
                    fsync_directory(folder)
                    connection.execute("INSERT INTO jobs(job_id,request_id,request_sha,archive_sha,state,stage,created,updated,heartbeat,deadline) VALUES(?,?,?,?,?,?,?,?,?,?)",
                        (job_id, request["requestId"], request_sha, actual, "queued", "admitted", now, now, now,
                         now + self.config["jobTimeoutSeconds"]))
            return self.status(job_id)
        finally:
            shutil.rmtree(stage, ignore_errors=True)

    def cancel(self, job_id):
        row = self.row(job_id)
        if row["state"] in TERMINAL:
            return self.status(job_id)
        update(self.root, job_id, state="cancelling", stage="cancellation_requested", code="CANCELLED_BY_CLIENT")
        _kill_group(row)
        update(self.root, job_id, state="cancelled", stage="cancelled", pid=None, start_ticks=None)
        return self.status(job_id)

    def result(self, job_id):
        row = self.row(job_id)
        if row["state"] != "completed":
            raise ValueError("RESULT_NOT_READY")
        path = owned(self.root / "jobs" / job_id, "result.vsresult")
        if path.stat().st_size != row["result_bytes"] or digest(path) != row["result_sha"]:
            raise ValueError("PUBLISHED_RESULT_BYTES_CHANGED")
        return path, row["result_sha"], row["result_bytes"]


class Runner:
    """One resource-admitted worker at a time; HTTP reads stay independently available."""
    def __init__(self, config):
        self.store, self.config = Store(config), config
        self.lock = (self.store.root / "runner.lock").open("a+b")
        try:
            fcntl.flock(self.lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            self.lock.close(); raise RuntimeError("Another runner owns this job root") from None
        self.children, self.logs = {}, {}
        self.mutex = threading.Lock()
        self.stop = threading.Event()
        self.recover()

    def recover(self):
        with _connect(self.store.root) as connection:
            rows = [dict(row) for row in connection.execute("SELECT * FROM jobs WHERE state IN ('running','cancelling')")]
        for row in rows:
            _kill_group(row)
            if row["state"] == "cancelling":
                update(self.store.root, row["job_id"], state="cancelled", stage="cancelled", pid=None, start_ticks=None)
            else:
                update(self.store.root, row["job_id"], state="checkpointed", code="RUNNER_RESTARTED", pid=None, start_ticks=None)

    def tick(self):
        with self.mutex:
            now = time.time()
            with _connect(self.store.root) as connection:
                rows = [dict(row) for row in connection.execute("SELECT * FROM jobs WHERE state NOT IN ('completed','failed','cancelled','expired') ORDER BY created")]
            for row in rows:
                job_id = row["job_id"]
                if now >= row["deadline"]:
                    _kill_group(row)
                    update(self.store.root, job_id, state="expired", stage="deadline_expired", code="JOB_DEADLINE_EXCEEDED", pid=None, start_ticks=None)
                elif row["state"] == "running" and now - row["heartbeat"] > 120:
                    _kill_group(row)
                    update(self.store.root, job_id, state="failed", stage="heartbeat_expired", code="WORKER_HEARTBEAT_LOST", pid=None, start_ticks=None)
            for job_id, process in list(self.children.items()):
                log = self.logs[job_id]
                row = self.store.row(job_id)
                if log.stat().st_size > 8 * 1024**2:
                    _kill_group(row)
                    update(self.store.root, job_id, state="failed", code="WORKER_LOG_BUDGET_EXCEEDED", stage="failed")
                if process.poll() is not None:
                    try:
                        os.killpg(process.pid, signal.SIGKILL)
                    except ProcessLookupError:
                        pass
                    process.wait()
                    row = self.store.row(job_id)
                    if row["state"] == "running":
                        update(self.store.root, job_id, state="failed", stage="worker_exit", code="WORKER_EXIT_%d" % process.returncode)
                    del self.children[job_id]; del self.logs[job_id]
            if self.children:
                return
            with _connect(self.store.root) as connection:
                pending = [dict(row) for row in connection.execute("SELECT * FROM jobs WHERE state IN ('queued','checkpointed','waiting_compute','waiting_model') ORDER BY created")]
            for row in pending:
                job_id = row["job_id"]
                request = read_json(self.store.root / "jobs" / job_id / "input" / "request.json")
                state, code = admission(self.config, request)
                if state != "queued":
                    if code == "COGVIDEOX_RUNTIME_MISSING" and self.config.get("_runtimeDiagnostic"):
                        atomic_json(self.store.root / "jobs" / job_id / "runtime-diagnostic.json", self.config["_runtimeDiagnostic"])
                    update(self.store.root, job_id, state=state, code=code, heartbeat=now)
                    continue
                folder = self.store.root / "jobs" / job_id
                log_path = folder / "worker.log"
                with log_path.open("ab") as log:
                    process = subprocess.Popen([self.config["pythonExecutable"], "-m", "openpipeline.worker",
                        "--config", self.config["_configPath"], "--job-id", job_id],
                        cwd=Path(__file__).resolve().parents[1], stdout=log, stderr=subprocess.STDOUT,
                        stdin=subprocess.DEVNULL, start_new_session=True, env=dict(os.environ, HF_HUB_OFFLINE="1", TRANSFORMERS_OFFLINE="1"))
                self.children[job_id], self.logs[job_id] = process, log_path
                # Worker waits for this launch receipt before performing any effect.
                update(self.store.root, job_id, state="running", stage="worker_launch", code=None,
                       pid=process.pid, start_ticks=_start_ticks(process.pid), heartbeat=time.time())
                break

    def run_job(self, job_id):
        while not self.stop.is_set():
            self.tick()
            status = self.store.status(job_id)
            if status["state"] in TERMINAL or status["state"] in ("waiting_compute", "waiting_model"):
                if status["state"] == "completed":
                    path, sha, size = self.store.result(job_id)
                    status.update(resultPath=str(path), resultSha256=sha, resultBytes=size)
                return status
            time.sleep(.1)
        return self.store.status(job_id)

    def serve_loop(self):
        while not self.stop.wait(.5):
            self.tick()

    def close(self):
        self.stop.set()
        with self.mutex:
            for job_id, process in list(self.children.items()):
                row = self.store.row(job_id)
                _kill_group(row)
                process.wait(timeout=5)
                if row["state"] not in TERMINAL:
                    update(self.store.root, job_id, state="checkpointed", code="RUNNER_SHUTDOWN", pid=None, start_ticks=None)
            self.children.clear()
            fcntl.flock(self.lock, fcntl.LOCK_UN); self.lock.close()


def publish(store, job_id, outputs, execution):
    """Manifest binds request and exact streamed output bytes before DB completion."""
    row = store.row(job_id)
    if row["state"] != "running":
        raise InterruptedError("Job no longer permits publication")
    folder = store.root / "jobs" / job_id
    request = read_json(folder / "input" / "request.json")
    stage = folder / "result-stage"
    if stage.exists():
        shutil.rmtree(stage)
    stage.mkdir()
    files = []
    for index, (source, role, mime) in enumerate(outputs):
        name = "outputs/%02d_%s" % (index, source.name)
        path = stage / name; path.parent.mkdir(exist_ok=True)
        before, size = digest(source), source.stat().st_size
        shutil.copyfile(source, path)
        if path.stat().st_size != size or digest(path) != before:
            raise ValueError("Backend output changed during publication")
        files.append({"path": name, "role": role, "mime": mime, "bytes": size, "sha256": before})
    manifest = {"schema": RESULT_SCHEMA, "jobId": job_id, "state": "completed", "requestId": row["request_id"],
        "requestSha256": row["request_sha"], "projectId": request["projectId"],
        "sourceProjectUpdatedAt": request["sourceProjectUpdatedAt"], "files": files, "actualExecution": execution}
    atomic_json(stage / "manifest.json", manifest)
    target = folder / "result.vsresult"
    if target.exists():
        # A crash after archive publication may safely finalize the immutable archive.
        import zipfile
        from .archive import json_bytes
        with zipfile.ZipFile(target) as archive:
            if json_bytes(archive.read("manifest.json")) != manifest:
                raise ValueError("Published result belongs to a different generation")
            for item in files:
                value = hashlib.sha256(); count = 0
                with archive.open(item["path"]) as stream:
                    for block in iter(lambda: stream.read(128 * 1024), b""):
                        value.update(block); count += len(block)
                if count != item["bytes"] or value.hexdigest() != item["sha256"]:
                    raise ValueError("Published result bytes changed")
        receipt = {"sha256": digest(target), "bytes": target.stat().st_size}
    else:
        receipt = package(stage, ["manifest.json"] + [item["path"] for item in files], target)
    with _connect(store.root) as connection:
        connection.execute("BEGIN IMMEDIATE")
        current = connection.execute("SELECT state FROM jobs WHERE job_id=?", (job_id,)).fetchone()[0]
        if current != "running":
            raise InterruptedError("Cancelled or expired job cannot publish")
        connection.execute("UPDATE jobs SET state='completed',stage='published',result_sha=?,result_bytes=?,updated=?,heartbeat=?,pid=NULL,start_ticks=NULL WHERE job_id=?",
                           (receipt["sha256"], receipt["bytes"], time.time(), time.time(), job_id))
    shutil.rmtree(stage)
    return store.status(job_id)
