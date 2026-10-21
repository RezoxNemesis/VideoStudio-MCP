# Character dialogue: native controls and autonomous MCP

Source candidate 4.7.0 adds the same timed dialogue workflow to the native Open
Production Workbench and `app_open_pipeline_export_request`. The speech model runs
on the owner's connected inference computer. Installing this source alone does
not put a speech model on an Android device or configure its runner connection.

## Manual workflow

Open a native project and its Open Production Workbench. Choose `kokoro`, then
English or Hindi. Add character IDs, voice choices, dialogue, start times and
available time per line. Choose the scene duration. No JSON editing is needed.

Export the request, submit it to the configured runner, check progress, download
the completed result, and import it. Folder-scoped Drive exchange remains an
alternative to direct HTTPS. Imported dialogue creates a **separate copy of the
source project**, with the original edit retained, individual voice recordings in
its Media Bin, and one editable audio lane for the timed dialogue master. The
original project is preserved. A free audio lane is required.

`Resume saved dialogue request` reopens dialogue exported through either the
manual form or MCP. Its character IDs, voice IDs, text and timing survive. After
changing an exported request, choose `New request ID` before exporting another
version. Existing job IDs remain immutable and retained for recovery.

## Autonomous workflow

Call `app_status` first. Native 4.7.0 or newer is required before queue admission.
Use project/revision values actually returned by the app:

```json
{
  "projectId": "film_project",
  "sourceProjectUpdatedAt": 123,
  "requestId": "fight_hi_dialogue_01",
  "backend": "kokoro",
  "assetIds": [],
  "controls": {
    "language": "hi-IN",
    "durationMs": 120000,
    "lines": [
      {"lineId":"line_01","characterId":"kael","voice":"hm_omega",
       "text":"मैं लड़ना नहीं चाहता।","startMs":4000,"slotMs":5000,"speed":1.0},
      {"lineId":"line_02","characterId":"sera","voice":"hf_alpha",
       "text":"तो अपनी तलवार नीचे रखो।","startMs":10000,"slotMs":5000,"speed":1.0}
    ]
  }
}
```

Submit, poll and import using the existing `app_open_pipeline_*` tools. Export is
not inference completion. Imported audio is not a finished film. No Gallery
access, arbitrary source URL, model URL or executable can be supplied in a job.

## Runner setup

Use Python 3.11/3.12 and a dedicated environment. Install CPU Torch 2.8.0 from the
official PyTorch CPU wheel index, then `openpipeline/requirements-speech.txt`.
Run `python scripts/prepare-speech-model.py --output /your/model/directory`.
It downloads the pinned Apache-2.0 Kokoro model and four voice tensor packs,
checks every byte count/hash, and prints the `kokoro` configuration block. Add
that block to the trusted runner configuration described in `OPEN_PIPELINE.md`.
Keep credentials in the existing protected runner connection, outside source.

The configured Python executable must contain the speech dependencies. English
G2P must already be installed; jobs do not download models. Both model and voice
hashes are checked before and after inference. PyTorch uses `weights_only=True`
for this backend's pinned `.pth`/`.pt` tensors. Arbitrary pickle/code artifacts
remain disallowed. This backend does not change CogVideoX's Safetensors policy.

## Limits and evidence

* English: Heart (`af_heart`) and Adam (`am_adam`). Hindi: Alpha (`hf_alpha`) and
  Omega (`hm_omega`). These are synthetic stock voices, not cloned actors.
* Each request: 1..32 lines, 240 characters per line, 6,000 characters total,
  at most ten minutes. Split larger scenes into requests. Slots do not overlap;
  each character retains one explicitly chosen voice within a request.
* Speech is actual 24 kHz mono PCM. A line exceeding its allotted time fails with
  `DIALOGUE_EXCEEDS_SLOT`; it is not cut or automatically sped up. Invalid or
  silent waveforms fail. Peak attenuation retains headroom without hard clipping.
* The master includes authored silence between lines. Separate stems and timing
  metadata remain available. Music, ambience and sound effects need separate
  audio lanes and a reviewed mix.
* Model phoneme durations are supplied as a lip-sync starting point. They are
  **not forced alignment or proof of correct mouth animation**. The backend has
  no supported emotional acting control. Different language packs do not prove
  the same actor identity across languages.
* Workspace verification: real CPU inference in both languages, two stock voices
  per language, native request export, WAV/cast/timing validation, native project
  import, editable master lane and source preservation. Manual form and request
  reopening tests use the same operations. The installed MCP SDK also verifies
  exact queued speech parameters and rejection by an older native version.
* Device execution, live owner MCP connection, audible acting/pronunciation
  acceptance, final film lip sync and cinematic quality remain unverified.
