"""Operator commands: pack, run, capabilities, serve, status and cancel."""
import argparse
import json
from pathlib import Path
import sys

from .archive import pack_job
from .config import capabilities, load
from .jobs import Runner, Store
from .server import serve


def main(argv=None):
    parser = argparse.ArgumentParser(prog="python -m openpipeline")
    commands = parser.add_subparsers(dest="command", required=True)
    pack = commands.add_parser("pack")
    pack.add_argument("--request", required=True, type=Path)
    pack.add_argument("--sources", required=True, type=Path)
    pack.add_argument("--output", required=True, type=Path)
    for name in ("run", "capabilities", "serve", "status", "cancel"):
        command = commands.add_parser(name)
        command.add_argument("--config", required=True, type=Path)
        if name == "run":
            command.add_argument("--job", required=True, type=Path)
        if name in ("status", "cancel"):
            command.add_argument("--job-id", required=True)
    args = parser.parse_args(argv)
    try:
        if args.command == "pack":
            result = pack_job(args.request, args.sources, args.output)
        else:
            config = load(args.config)
            if args.command == "serve":
                serve(config); return 0
            elif args.command == "capabilities":
                result = capabilities(config)
            elif args.command in ("status", "cancel"):
                store = Store(config)
                result = store.status(args.job_id) if args.command == "status" else store.cancel(args.job_id)
            else:
                runner = Runner(config)
                try:
                    submitted = runner.store.submit(args.job)
                    result = runner.run_job(submitted["jobId"])
                finally:
                    runner.close()
        print(json.dumps(result, ensure_ascii=False, allow_nan=False))
        return 69 if result.get("state") in ("waiting_compute", "waiting_model") else 1 if result.get("state") in ("failed", "expired", "cancelled") else 0
    except KeyboardInterrupt:
        return 130
    except (OSError, ValueError, KeyError, RuntimeError) as error:
        print(json.dumps({"state": "failed", "code": type(error).__name__, "message": str(error)[:200], "nativeRendered": False}))
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
