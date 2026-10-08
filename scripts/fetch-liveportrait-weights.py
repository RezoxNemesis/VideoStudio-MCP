#!/usr/bin/env python3
"""Fetch the pinned official animal checkpoints. These are PyTorch files, not an Android pack."""
import argparse
import concurrent.futures
import hashlib
import json
from pathlib import Path
import urllib.request

REPOSITORY = "KlingTeam/LivePortrait"
REVISION = "82a4fa6735ca58432b6ce39301b4b9ee066dea47"
FILES = {
    "appearance_feature_extractor.pth": "7e320d545579caa83c7b094cef8b7b43fe92a2e410c219ffa97b08be549f45bf",
    "motion_extractor.pth": "827d0ea4c56ff252dba50feece3bc62ced365ecae5edb86db07eb71b2f39a696",
    "spade_generator.pth": "6ac31a9b608f3920ec41a402b1c4e29d22007dafd79a855f204aae9307039445",
    "warping_module.pth": "7dfd251dc6b3a1baefebfc658f5bb2a2c565649cdb9aa75032591e824a0bfcee",
}

def download(destination, name, expected):
    path = destination / name
    if path.is_file():
        with path.open("rb") as stream:
            if hashlib.file_digest(stream, "sha256").hexdigest() == expected:
                return {"name": name, "verified": True, "cached": True}
        raise RuntimeError("Existing checkpoint checksum mismatch: " + name)
    url = f"https://huggingface.co/{REPOSITORY}/resolve/{REVISION}/liveportrait_animals/base_models_v1.1/{name}"
    temporary = path.with_suffix(".pth.partial")
    digest = hashlib.sha256()
    with urllib.request.urlopen(url, timeout=60) as response, temporary.open("wb") as stream:
        while chunk := response.read(1024 * 1024):
            stream.write(chunk)
            digest.update(chunk)
    if digest.hexdigest() != expected:
        raise RuntimeError("Downloaded checkpoint checksum mismatch: " + name)
    temporary.replace(path)
    return {"name": name, "verified": True, "bytes": path.stat().st_size}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--destination", type=Path, required=True)
    args = parser.parse_args()
    args.destination.mkdir(parents=True, exist_ok=True)
    failures = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as executor:
        pending = {executor.submit(download, args.destination, name, expected): name
                   for name, expected in FILES.items()}
        for future in concurrent.futures.as_completed(pending):
            try:
                print(json.dumps(future.result()), flush=True)
            except Exception as error:
                failures.append(pending[future])
                print(json.dumps({"name": pending[future], "verified": False,
                                  "error": str(error)}), flush=True)
    if failures:
        raise SystemExit("No native model readiness claim: download failed for " + ", ".join(failures))
    (args.destination / "liveportrait-provenance.json").write_text(json.dumps({
        "repository": REPOSITORY, "revision": REVISION, "files": FILES,
        "androidCompatible": False, "trainedInferenceValidated": False,
        "nextStep": "Validate this cartoon, export real ONNX stages and implement the native ABI"
    }, indent=2) + "\n")

if __name__ == "__main__":
    main()
