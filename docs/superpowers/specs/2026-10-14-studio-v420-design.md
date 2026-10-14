# Video studio 4.2.0 design

The owner requested substantial improvements across Video studio, preserving
human and ChatGPT access to the same native editing functions. Existing requests
require responsive phone interaction, genuine authored drawing, reliable private
media transfer, and truthful evidence. Routine design, GitHub and workspace
approval questions were explicitly waived; work proceeds autonomously.

| Area | Deliverable | Acceptance boundary |
| --- | --- | --- |
| Editor | Exact playhead split/trim, source/output clock mapping, cut navigation, timeline zoom/snap and left/right trim gestures | Transaction/history and gesture regressions; no silent midpoint fallback |
| Audio/export | Per-clip output-time fades, safe gain saturation, preview/export shared composition | PCM samples, seek/trim/speed and split-continuity tests |
| Sketch | Exact large-book navigation, exposure sheet and hold presets, persistent onion settings, stable inertial filmstrip | Actual Activity and native book/CAS tests; retain original-stroke policy and notifications |
| 3D/rig | Single renderer ownership, cancellable background loading, motion/scrub/rate/camera controls, rig pixel precision | Lifecycle, playback-clock and coordinate validation tests; GPU/device acceptance remains separate |
| Private storage | Durable chunked upload/export and correct protocol queue, retained owner/hash/expiry identity | Real SQLite workerd restart with a 14.7 MB file, integrity, quota, authorization and rollback tests |
| Autonomous import | Encrypted immutable GitHub media fallback using the existing owner key and backup channel | Cross-language AES-GCM vector, interruption/resume, exact hashes and native private-file publication |
| Projects | Large SQLite metadata reads and transactional import registration against current project | Large-row, simultaneous imports, duplicate delivery and concurrent-human-edit regressions |

The secondary media transport publishes ciphertext only. It fixes no Cloudflare
deployment by itself: the installed APK and published backend source are separate
release states. Source data must enter through native import surfaces; Gallery
browsing and external rendering presented as native evidence remain prohibited.

The native media wire uses `videostudio.agent.media.v1`, HMAC-SHA256 key derivation
with the distinct `VideoStudio media encryption v1` label and AES-256-GCM. AAD binds
channel, attachment UUID and chunk index. Authenticated manifests bind filename,
MIME, exact size, SHA-256, chunk dimensions and expiry. Chunk size is 524288 bytes;
the fallback is bounded at 256 MiB and seven days. Complete verified files publish
atomically in app-private storage. No credentials or plaintext media enter Git.

The package stays `com.rezoxnemesis.videostudio.next`; the pinned original signer,
device-owned connection identity, projects and private originals are preserved.
Version 4.2.0 / code 420 gates encrypted media import and audio fade commands.
No neural reconstruction, likeness, cinematic quality or physical phone success
is implied by compilation, tests or a signed APK.
