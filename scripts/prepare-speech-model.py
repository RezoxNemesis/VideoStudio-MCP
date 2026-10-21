#!/usr/bin/env python3
"""Prepare public, hash-pinned Kokoro tensor weights; never executes model files."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import tempfile
import urllib.request

REVISION = "f3ff3571791e39611d31c381e3a41a3af07b4987"
FILES = (
    ("config.json", 2351, "5abb01e2403b072bf03d04fde160443e209d7a0dad49a423be15196b9b43c17f"),
    ("kokoro-v1_0.pth", 327212226, "496dba118d1a58f5f3db2efc88dbdc216e0483fc89fe6e47ee1f2c53f18ad1e4"),
    ("voices/af_heart.pt", 523425, "0ab5709b8ffab19bfd849cd11d98f75b60af7733253ad0d67b12382a102cb4ff"),
    ("voices/am_adam.pt", 523420, "ced7e284aba12472891be1da3ab34db84cc05cc02b5889535796dbf2d8b0cb34"),
    ("voices/hf_alpha.pt", 523425, "06906fe05746d13a79c5c01e21fd7233b05027221a933c9ada650f5aafc8f044"),
    ("voices/hm_omega.pt", 523425, "b55f02a8e8483fffe0afa566e7d22ed8013acf47ad4f6bbee2795a840155703e"),
)


def prepare(root):
    root = Path(root).absolute(); root.mkdir(parents=True, exist_ok=True)
    records = []
    for name, size, expected in FILES:
        target = root / name
        target.parent.mkdir(exist_ok=True)
        if target.is_symlink(): raise ValueError("Model artifact cannot be a symlink")
        if not target.exists():
            fd, tmp = tempfile.mkstemp(prefix=".download-", dir=target.parent)
            try:
                sha, count = hashlib.sha256(), 0
                with os.fdopen(fd, "wb") as out, urllib.request.urlopen("https://huggingface.co/hexgrad/Kokoro-82M/resolve/" + REVISION + "/" + name, timeout=120) as response:
                    while block := response.read(128 * 1024):
                        count += len(block)
                        if count > size: raise ValueError("Model exceeds pinned size")
                        sha.update(block); out.write(block)
                    out.flush(); os.fsync(out.fileno())
                if count != size or sha.hexdigest() != expected: raise ValueError("Public model checksum differs")
                os.link(tmp, target)
            finally:
                Path(tmp).unlink(missing_ok=True)
        sha = hashlib.sha256()
        with target.open("rb") as stream:
            for block in iter(lambda: stream.read(128 * 1024), b""): sha.update(block)
        if target.stat().st_size != size or sha.hexdigest() != expected:
            raise ValueError("Existing model differs; preserve it and choose another directory")
        records.append({"path": name, "bytes": size, "sha256": expected})
    manifest = {"schema": "videostudio.speech.model.v1", "modelId": "hexgrad/Kokoro-82M", "revision": REVISION, "license": "apache-2.0", "files": records}
    raw = (json.dumps(manifest, indent=2) + "\n").encode()
    target = root / "videostudio-speech-manifest.json"
    if target.exists():
        if target.is_symlink() or target.read_bytes() != raw: raise ValueError("Existing manifest differs")
    else:
        with target.open("xb") as out: out.write(raw)
    return {"modelPath": str(root), "manifestSha256": hashlib.sha256(raw).hexdigest(), "revision": REVISION, "cpuThreads": 4}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__); parser.add_argument("--output", required=True, type=Path)
    print(json.dumps({"kokoro": prepare(parser.parse_args().output)}, indent=2))
