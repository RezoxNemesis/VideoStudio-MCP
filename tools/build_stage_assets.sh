#!/usr/bin/env bash
# Build everything the 3D Stage, the Music section and the open-prosody voice need at run time into one folder
# (default desktop/stage-assets). The repository stays light: nothing built here is committed.
#
#   human.pack            bodies: MakeHuman base mesh, rig, weights, shape and expression targets (CC0 assets)
#   motion/               2,500+ captured performances from the CMU Graphics Lab database (free for all uses)
#   music/                GeneralUser GS instrument bank (261 instruments, 13 drum kits; free for any use)
#   voice/                Kokoro (Apache-2.0) split so timing and pitch can be read and set, plus the voices used
#   models/               854 ready 3D models for the Stage: the KayKit packs by Kay Lousberg (CC0 1.0)
#
# usage: tools/build_stage_assets.sh [out dir] [work dir]
# needs: git, curl, python3 with numpy and onnx (pip install numpy onnx), and `npm ci` done at the repo root.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="${1:-$ROOT/desktop/stage-assets}"
WORK="${2:-$ROOT/.stage-build}"
JOBS="${JOBS:-$(nproc 2>/dev/null || echo 2)}"
mkdir -p "$OUT" "$WORK"

# Pinned sources (the packs are reproducible from these).
MH_REPO=https://github.com/makehumancommunity/makehuman.git
MH_REV=a8bc2d54ff0ac92e78ff71431b1023eda42bf482
CMU_REPO=https://github.com/una-dinosauria/cmu-mocap.git
CMU_REV=09a07f54f3bbb58797325f009282d0b2048a2871
SF_REPO=https://github.com/mrbumpy409/GeneralUser-GS.git
SF_REV=684543d5e5efaef08d02be50dcda8d552478fa60
KOKORO_URL=https://github.com/thewh1teagle/kokoro-onnx/releases/download/model-files-v1.0/kokoro-v1.0.int8.onnx
KOKORO_SHA=6e742170d309016e5891a994e1ce1559c702a2ccd0075e67ef7157974f6406cb
VOICES="af_alloy af_aoede af_bella af_heart af_jessica af_kore af_nicole af_nova af_river af_sarah af_sky am_adam am_echo am_eric am_fenrir am_liam am_michael am_onyx am_puck am_santa bf_alice bf_emma bf_isabella bf_lily bm_daniel bm_fable bm_george bm_lewis ef_dora em_alex em_santa ff_siwis hf_alpha hf_beta hm_omega hm_psi if_sara im_nicola pf_dora pm_alex pm_santa"

fetch() { # fetch <repo> <rev> <dir> [git clone options]
  local repo="$1" rev="$2" dir="$3"; shift 3
  if [ ! -d "$dir/.git" ]; then git clone --no-checkout "$@" "$repo" "$dir"; fi
  git -C "$dir" fetch --depth 1 origin "$rev" 2>/dev/null || git -C "$dir" fetch origin
  git -C "$dir" checkout -q "$rev"
}

if [ ! -s "$OUT/human.pack" ]; then
  echo "== bodies"
  fetch "$MH_REPO" "$MH_REV" "$WORK/makehuman" --depth 1
  python3 "$ROOT/tools/stage/build_human_pack.py" --mh "$WORK/makehuman/makehuman/data" --out "$OUT/human.pack"
fi

if [ ! -s "$OUT/motion/index.json" ]; then
  echo "== motion capture (this is the long one)"
  fetch "$CMU_REPO" "$CMU_REV" "$WORK/cmu" --filter=blob:none
  python3 "$ROOT/tools/stage/build_motion_pack.py" --cmu "$WORK/cmu" --out "$OUT/motion" --jobs "$JOBS"
fi

if [ ! -s "$OUT/music/GeneralUser-GS.sf2" ]; then
  echo "== instrument bank"
  fetch "$SF_REPO" "$SF_REV" "$WORK/gugs" --depth 1
  mkdir -p "$OUT/music"
  cp "$WORK/gugs/GeneralUser-GS.sf2" "$OUT/music/GeneralUser-GS.sf2"
  cp "$WORK/gugs/documentation/LICENSE.txt" "$OUT/music/GeneralUser-GS-LICENSE.txt"
fi

if [ ! -s "$OUT/voice/voc.onnx" ]; then
  echo "== voice model"
  M="$WORK/kokoro-v1.0.int8.onnx"
  [ -s "$M" ] || curl -fL --retry 3 -o "$M" "$KOKORO_URL"
  echo "$KOKORO_SHA  $M" | sha256sum -c -
  python3 "$ROOT/tools/voice/split_kokoro.py" "$M" "$OUT/voice"
fi
mkdir -p "$OUT/voice/voices"
for v in $VOICES; do
  [ -s "$OUT/voice/voices/$v.bin" ] || cp "$ROOT/node_modules/kokoro-js/voices/$v.bin" "$OUT/voice/voices/$v.bin"
done

# The 3D model library of the Stage: the KayKit packs by Kay Lousberg (CC0 1.0), copied as they are with a catalogue.
if [ ! -s "$OUT/models/index.json" ]; then
  echo "== 3D model library"
  while read -r name rev; do
    fetch "https://github.com/KayKit-Game-Assets/$name.git" "$rev" "$WORK/kaykit/$name" --depth 1
  done <<'PACKS'
kaykit-city-builder-bits-1.0 63976910ca04d16f0fc531b9c614244be8128713
kaykit-furniture-bits-1.0 96d5930a8dbdb363409bbc2d3341718b00e17c9c
kaykit-dungeon-remastered-1.0 b0ca9bd96a8072ab36a3a5464f00ed1e06a16d07
kaykit-restaurant-bits-1.0 153c8a7535b48237854cb54ff6890679f8c574d1
kaykit-space-base-bits-1.0 6dfbcac9927d06283752c4defd4882cfe0d29666
kaykit-medieval-hexagon-pack-1.0 84fa4e91af6a88989be7c99e0891cede11f2ca38
kaykit-prototype-bits-1.0 bb159596f4f5106b663741d002c8eb45c80c0f41
kaykit-halloween-bits-1.0 6dc69bf6b2fa766a985754f35ec6a0324090e6c6
PACKS
  python3 -I "$ROOT/tools/pack_models.py" "$WORK/kaykit" "$OUT/models"
fi

echo "== done"
du -sh "$OUT"/* | sed 's/^/  /'
