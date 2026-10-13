"""Authenticated bounded HTTP exchange and optional mounted-folder watcher."""
from __future__ import annotations
import hmac
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import os
from pathlib import Path
import re
import shutil
import socket
import tempfile
import threading
import time

from .archive import MAX_ARCHIVE, atomic_json, checksum, digest, encode, fsync_directory, identifier
from .config import capabilities
from .exchange import ExchangeUnavailable, copy_verified
from .jobs import Runner, TERMINAL


class BoundedServer(ThreadingHTTPServer):
    daemon_threads = True
    request_queue_size = 16
    def __init__(self, address, handler, runner, token):
        self.slots = threading.BoundedSemaphore(16)
        self.runner, self.token = runner, token
        super().__init__(address, handler)
    def process_request(self, request, client_address):
        if not self.slots.acquire(blocking=False):
            request.close(); return
        try:
            super().process_request(request, client_address)
        except BaseException:
            self.slots.release(); raise
    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            self.slots.release()


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "VideoStudioOpenRunner/1"
    def setup(self):
        super().setup()
        self.connection.settimeout(20)
    def log_message(self, *_):
        pass  # Never log bearer headers, private prompts or remote response bodies.
    def _json(self, status, body):
        raw = encode(body)
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(raw)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("Connection", "close")
        self.end_headers(); self.wfile.write(raw)
        self.close_connection = True
    def _auth(self):
        expected = "Bearer " + self.server.token
        provided = self.headers.get("Authorization", "")
        if not hmac.compare_digest(provided.encode(), expected.encode()):
            self._json(401, {"code": "AUTHENTICATION_REQUIRED"})
            return False
        return True
    def _route(self):
        if "?" in self.path or "#" in self.path or "%" in self.path:
            raise ValueError("Invalid route")
        if self.path == "/v1/capabilities":
            return "capabilities", None
        if self.path == "/v1/jobs":
            return "jobs", None
        match = re.fullmatch(r"/v1/jobs/([A-Za-z0-9_-]{1,96})(?:/(cancel|result))?", self.path)
        if match:
            return match[2] or "status", match[1]
        raise KeyError("Unknown route")
    def do_GET(self):
        if not self._auth():
            return
        try:
            route, job_id = self._route()
            store = self.server.runner.store
            if route == "capabilities":
                self._json(200, capabilities(self.server.runner.config))
            elif route == "status":
                self._json(200, store.status(job_id))
            elif route == "result":
                path, sha, size = store.result(job_id)
                self.send_response(200)
                self.send_header("Content-Type", "application/vnd.videostudio.vsresult")
                self.send_header("Content-Length", str(size))
                self.send_header("X-VideoStudio-SHA256", sha)
                self.send_header("Cache-Control", "no-store")
                self.send_header("Connection", "close")
                self.end_headers()
                with path.open("rb") as stream:
                    shutil.copyfileobj(stream, self.wfile, 128 * 1024)
                self.close_connection = True
            else:
                self._json(405, {"code": "METHOD_NOT_ALLOWED"})
        except KeyError:
            self._json(404, {"code": "NOT_FOUND"})
        except (OSError, ValueError):
            self._json(409, {"code": "RESULT_UNAVAILABLE_OR_INVALID"})
    def do_POST(self):
        if not self._auth():
            return
        temporary = None
        try:
            route, job_id = self._route()
            if route == "cancel" and not self.headers.get("Transfer-Encoding") and not self.headers.get_all("Content-Length", []):
                self._json(200, self.server.runner.store.cancel(job_id))
                return
            if self.headers.get("Transfer-Encoding") or len(self.headers.get_all("Content-Length", [])) != 1:
                raise ValueError("A single bounded Content-Length is required")
            length_text = self.headers["Content-Length"]
            if not re.fullmatch(r"[0-9]{1,10}", length_text):
                raise ValueError("Invalid Content-Length")
            length = int(length_text)
            if route == "cancel":
                if length != 0:
                    raise ValueError("Cancel has an empty body")
                self._json(200, self.server.runner.store.cancel(job_id))
                return
            if route != "jobs":
                self._json(405, {"code": "METHOD_NOT_ALLOWED"}); return
            if not 1 <= length <= MAX_ARCHIVE or self.headers.get("Content-Type", "").split(";", 1)[0] != "application/vnd.videostudio.vsjob":
                raise ValueError("Invalid bounded job body or MIME")
            sha = checksum(self.headers.get("X-VideoStudio-SHA256"))
            key = identifier(self.headers.get("Idempotency-Key"))
            fd, name = tempfile.mkstemp(prefix=".http-upload-", dir=self.server.runner.store.root / "incoming")
            temporary = Path(name)
            deadline, remaining = time.monotonic() + 120, length
            with os.fdopen(fd, "wb") as output:
                while remaining:
                    if time.monotonic() > deadline:
                        raise TimeoutError("Upload deadline exceeded")
                    block = self.rfile.read(min(128 * 1024, remaining))
                    if not block:
                        raise ValueError("Incomplete job body")
                    output.write(block); remaining -= len(block)
                output.flush(); os.fsync(output.fileno())
            receipt = self.server.runner.store.submit(temporary, archive_sha=sha, idempotency_key=key)
            self._json(202, receipt)
        except KeyError:
            self._json(404, {"code": "NOT_FOUND"})
        except (OSError, ValueError) as error:
            code = str(error)
            # Expose stable admission codes, not paths or exception/body dumps.
            if not re.fullmatch(r"[A-Z][A-Z0-9_]{1,95}", code):
                code = "INVALID_REQUEST_ARCHIVE"
            self._json(409 if code == "IDEMPOTENCY_CONFLICT" else 400, {"code": code})
        finally:
            if temporary is not None:
                temporary.unlink(missing_ok=True)


def _copy_verified(source, target, *, expected_sha=None):
    return copy_verified(source, target, expected_sha=expected_sha)


def watch_mount(runner):
    """A mount is storage only. Never infer OAuth, cloud execution or remote durability."""
    config = runner.config
    inbox, results = Path(config["driveInbox"]), Path(config["driveResults"])
    while not runner.stop.wait(2):
        if not inbox.is_dir() or not results.is_dir():
            continue
        seen = 0
        for source in inbox.glob("*.vsjob"):
            seen += 1
            if seen > 256:
                break
            try:
                if source.is_symlink() or source.stat().st_size > MAX_ARCHIVE:
                    continue
                # Freeze a verified local snapshot; mount writers may still be uploading.
                frozen = runner.store.root / "incoming" / (".mount-request-" + os.urandom(16).hex())
                try:
                    snapshot = copy_verified(source, frozen)
                    receipt = runner.store.submit(frozen, archive_sha=snapshot["sha256"], idempotency_key=source.stem)
                finally:
                    frozen.unlink(missing_ok=True)
                if receipt["state"] == "completed":
                    output, sha, _ = runner.store.result(receipt["jobId"])
                    exchange_receipt = runner.store.root / "jobs" / receipt["jobId"] / "mount-exchange.json"
                    try:
                        proof = _copy_verified(output, results / (receipt["requestId"] + ".vsresult"), expected_sha=sha)
                    except ExchangeUnavailable as error:
                        proof = error.receipt
                    except (OSError, ValueError):
                        proof = {"state": "waiting_storage", "code": "MOUNT_COPY_OR_CONFLICT", "published": False,
                                 "remoteDrivePersistenceVerified": False}
                    atomic_json(exchange_receipt, proof)
            except (OSError, ValueError, KeyError):
                continue  # A later complete immutable upload can be admitted on retry.


def serve(config):
    token = os.environ.get(config["tokenEnv"])
    if token is None or len(token) < 24 or len(token) > 4096 or any(char.isspace() for char in token):
        raise ValueError("Set a strong bearer token in the configured environment variable")
    runner = Runner(config)
    server = BoundedServer((config["host"], config["port"]), Handler, runner, token)
    threads = [threading.Thread(target=runner.serve_loop, daemon=True)]
    if config.get("driveInbox") and config.get("driveResults"):
        threads.append(threading.Thread(target=watch_mount, args=(runner,), daemon=True))
    for thread in threads:
        thread.start()
    try:
        server.serve_forever(poll_interval=.5)
    finally:
        server.server_close(); runner.close()
        for thread in threads:
            thread.join(timeout=3)
