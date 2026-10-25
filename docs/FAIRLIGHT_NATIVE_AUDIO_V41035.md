# VideoStudio 4.10.35 — Native Fairlight-inspired Audio Studio

Original Android engineering inspired by professional DAWs. No Blackmagic
DaVinci Resolve/Fairlight proprietary source code, AAF/DRP project import,
audio plugin binaries, licensed presets, or Gallery access.

## Real implementation

- `StudioFairlightSettings` validates PCM-only source-preserving audio lane
  parameters with explicit type/value ranges.
- `StudioFairlightDsp` processes each *decoded PCM16 sample* with a causal
  three-band crossover EQ (approx. 200Hz / 3.5kHz), optional highpass/lowpass,
  feed-forward threshold-ratio compressor, gate, makeup gain and sample peak
  ceiling. Per-channel state is independent; 1..8 channels / 8..192kHz input
  accepted. This is a **native signal processor**, not a timeline sticker.
- `StudioFairlightAudioProcessor` connects DSP to Media3 `BaseAudioProcessor`,
  uses the library's reusable direct output buffer and preserves sample counts.
  One independent processor is configured for every owned audio lane.
- Original `StudioLayer.AUDIO` records support optional `fairlightFx`; if
  omitted, all previously saved track JSON and audio preview/export behavior
  remain unchanged.
- Native audio Inspector provides exact EQ, dynamics, filter and limiter
  controls as well as Dialogue, Music, Broadcast, Ambient and Neutral presets.
  Existing volume and waveform tools remain usable.
- `fairlight_fx_capabilities`, `fairlight_fx_plan`, `fairlight_fx_apply`
  allow one authenticated native MCP request to edit up to 16 owned audio lanes,
  with atomic revisions and no guessed success receipts.
- Tests measure the real processed PCM values, sample peak ceiling, compressor
  attenuation, frequency suppression, timing/sample-count preservation,
  validation, project ownership and MCP admission.

## Limits and QA

- **Not** Fairlight's thousands-of-tracks audio core.
- Sample-peak ceiling is not intersample *true-peak* oversampling, EBU-R128 LUFS
  compliance, calibrated Dolby/Atmos mastering, adaptive loudness automation,
  ADR synchronization, multiband mastering or on-device ML voice isolation.
- The on-device Media3 edited composition and H.264/AAC exported result must
  be verified by listening and comparing decoded audio; static unit tests are
  insufficient.
- Thermal budget, thread latency, dynamic allocation and recovery must be
  checked on the owner's Android device. Memory caches and original imported
  video/music files are never rewritten by this feature.
- No attempt was made to replace stable published downloads or the persistent
  private MCP identity.

## Follow-on gates

1. Playback/export failure `Test subject 2` full regression.
2. Waveform, peak and short-term RMS displays from real capture, plus separate
   source/final mix meter calibration.
3. EBU-R128 loudness and true-peak meter with explicit calibrated test vectors.
4. Curve-automated volume/pan/EQ and synchronised dialogue music ducking.
5. Fusion-style VFX nodes, tracked mattes and 2.5D/3D are independent upgrades
   requiring resource budgets and actual visual QA.

Status: source implementation. Unit and APK signing/compile proof reported
separately by GitHub Actions. Do not claim the whole DaVinci Resolve/Fairlight
suite is completed from this slice.
