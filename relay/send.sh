#!/usr/bin/env bash
# Send a file to the phone's VideoStudio inbox:  ./send.sh <relay-base-url> <device-key> <file> [name]
set -euo pipefail
base="${1%/}/device/$2/files"; file="$3"; name="${4:-$(basename "$file")}"
size=$(stat -c %s "$file"); mime=$(file -b --mime-type "$file" 2>/dev/null || echo application/octet-stream)
meta=$(jq -n --arg n "$name" --arg m "$mime" --argjson s "$size" '{name:$n,mime:$m,size:$s}')
resp=$(curl -fsS -X POST "$base" -H 'content-type: application/json' -d "$meta")
id=$(echo "$resp" | jq -r .id); chunk=$(echo "$resp" | jq -r .chunkSize)
n=$(( (size + chunk - 1) / chunk )); tmp=$(mktemp -d)
for ((i = 0; i < n; i++)); do
  dd if="$file" of="$tmp/c" bs="$chunk" skip="$i" count=1 status=none
  curl -fsS --retry 3 -X PUT "$base/$id/$i" -H 'content-type: application/octet-stream' --data-binary @"$tmp/c" >/dev/null
done
rm -rf "$tmp"; echo "$id"
