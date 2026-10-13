"""Fixed trusted child entrypoint; a request can never supply executable code."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import threading
import time

from .archive import digest, owned, read_json, validate_request
from .backends import Unavailable, execute
from .config import load
from .jobs import Store, publish, update


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", required=True, type=Path)
    parser.add_argument("--job-id", required=True)
    args = parser.parse_args()
    config = load(args.config); store = Store(config)
    # Parent persists the PID/start-tick receipt before this worker may execute.
    for _ in range(100):
        row = store.row(args.job_id)
        if row["state"] == "running" and row["pid"] == os.getpid():
            break
        time.sleep(.05)
    else:
        return 2
    done = threading.Event()
    def checkpoint(stage, progress=None):
        row = store.row(args.job_id)
        if row["state"] != "running" or time.time() >= row["deadline"]:
            raise InterruptedError("Job cancelled or deadline expired")
        values = {"stage": stage, "heartbeat": time.time()}
        if progress is not None:
            values["progress"] = json.dumps(progress, separators=(",", ":"))
        update(store.root, args.job_id, **values)
    def heartbeat():
        while not done.wait(2):
            try:
                update(store.root, args.job_id, heartbeat=time.time())
            except Exception:
                pass  # Parent heartbeat deadline remains authoritative.
    thread = threading.Thread(target=heartbeat, daemon=True); thread.start()
    try:
        folder = store.root / "jobs" / args.job_id
        source = folder / "input"
        request_path = owned(source, "request.json")
        if digest(request_path) != row["request_sha"]:
            raise ValueError("Immutable request bytes changed")
        request = validate_request(read_json(request_path))
        for record in request["sources"]:
            path = owned(source, record["path"])
            if path.stat().st_size != record["bytes"] or digest(path) != record["sha256"]:
                raise ValueError("Immutable source bytes changed")
        work = folder / "work"; work.mkdir(exist_ok=True)
        outputs, execution = execute(config, request, source, work, checkpoint)
        checkpoint("publishing")
        publish(store, args.job_id, outputs, execution)
        return 0
    except Unavailable as error:
        update(store.root, args.job_id, state=error.state, code=error.code, stage="provider_unavailable", pid=None, start_ticks=None)
        return 69
    except InterruptedError:
        return 70
    except BaseException as error:
        update(store.root, args.job_id, state="failed", stage="execution_failed", code=type(error).__name__ + ": " + str(error)[:200], pid=None, start_ticks=None)
        return 1
    finally:
        done.set(); thread.join(timeout=3)


if __name__ == "__main__":
    raise SystemExit(main())
