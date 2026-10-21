# Original speech fixtures

These are actual outputs of VideoStudio's existing `openpipeline` speech runner,
not sine-wave/mock speech or recordings taken from reference footage.
Generated 2026-10-18 from application source4b043a8 using pinned Apache-2.0
hexgrad/Kokoro-82M revision f3ff3571791e39611d31c381e3a41a3af07b4987.
The product's `scripts/prepare-speech-model.py` verifies every weight/voice hash.
Runtime pins are in `openpipeline/requirements-speech.txt`.

English: “Maybe we should go home.” Voice am_adam, speed1.
Hindi: “माँ, मुझे पानी दो।” Voice hf_alpha, speed1.
Both masters are six seconds, 24kHz mono PCM16. Speech starts at1000ms in a
4000ms slot; actual stems last2125ms and2350ms. Timing JSON is copied unchanged
from the product result and contains unaligned model predictions, not phoneme
recognition or verified forced alignment. No human intelligibility approval.

Full immutable requests, result archives, model hashes, PCM verification and
CLI reproduction are retained outside Git in
`/workspace/engineering-review/speech-animation-20261018/audio-runner`.
APK runtime does not depend on these test fixtures or the workspace Python model.
Manual/native/MCP speech authoring uses reviewed timed cues; the existing optional
self-hosted runner provides generated audio and duration predictions.
