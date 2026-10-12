# Open workbench engineering evidence — 2026-10-12

These checks cover the 3.9.0 implementation on the 3.8.1 recovery baseline.
They do not certify phone installation or cinematic acceptance.

| Check | Observed result |
| --- | --- |
| Combined Android JVM/native-Canvas suite | 575 tests, 118 suites, zero failures/skips |
| Shared authoring/Drive/import focused Android batch | 87 tests passed |
| Concurrent sketch completion regression after final assertion change | 6 tests passed |
| Worker source smoke / Node suite | 306 checks / 154 tests passed |
| Installed MCP SDK against local workerd and SQLite | 13 scenarios passed; native ACKs explicitly synthetic |
| Self-hosted job/archive/HTTP/cancellation/FFmpeg/mount suite | 21 tests passed |
| Actual Blender / camera / producer suite | 99 tests executed; 98 passed initially; the failed conditioning case and its three related checks passed after the causal fixes |
| Exact native shader sources under Mesa GLES2 | 12 measured pixel checks passed |
| Worker bundle | Wrangler dry-run passed |

The copied Blender scene initially lacked registered compositor depth sockets.
Explicit scene/layer binding and pass registration fixed that. Binary ID masks
then revealed an inherited dithering value of 1.0, producing 1/255 noise in zero
pixels. Dithering is disabled only on the private conditioning scene; the
original scene remains intact. Actual eight-frame pose/camera/depth/normal/
actor/prop output passed the unchanged strict assertions.

The generated extended offline archive is 898,024 bytes, SHA-256
`e598538e03c5d27db56c75781206005cb7adf001b3866539fe98e3c55231c46a`.
Its GLB contains two skins and 21 named clips, 3,232,236 bytes including an
829,920-byte JSON chunk. Dedicated GLB JSON remains bounded to 1 MiB; ordinary
timeline/manifest JSON retains the 512 KiB limit. Measured maximum foot residual
is 0.01062531 m and wrist socket residual is below 0.000001 m. The original
eleven-action archive remains byte-identical, SHA-256
`31a964d508e9f3c67ba8138f137400ab70a72db98f35c1675e1c6189834466f2`.

Actual CPU inference used public Apache-2.0 `zai-org/CogVideoX-2b`, revision
`1137dacfc2c9c012bed6a0793f4ecf2ca8e7ba01`. Fourteen files totaling
13,775,527,819 bytes were verified against official LFS/Git hashes and rechecked
locally. Manifest SHA-256:
`5ed6726944add2006c7195f9b5cd9d3076583eaf68c98a1444400694bdc17e13`.
Torch 2.8.0+cpu / Diffusers 0.35.2 executed with local Safetensors only.

- Text generation: 17 decoded H.264 frames, 256×144, 8 FPS, 2.125 seconds,
  12 inference steps; MP4 SHA-256
  `98968ceafd81dff558ca4b7b7a3cfcc029a8b42e89f8c13a14eec37067dfd0a4`.
- Video conditioning: nine decoded H.264 frames, 256×144, 8 FPS, 1.125 seconds,
  three observed denoising steps from 12 requested steps at strength 0.25;
  MP4 SHA-256
  `546b22258c3ed85118452215ccc46f79f5abd2f9baeec6d413a42ac541f20d0f`.
  Source indices, presentation times and prepared frame hashes were retained.
  Inference took 363.4 seconds on CPU under the concurrent development workload.

An incompatible 13-frame video request failed output-clock verification rather
than being published. The pinned VAE requires `8k+1` samples; native, Worker and
runner now reject incompatible counts before inference. Low-resolution probes
establish execution, not production quality or proprietary-model parity.

The authenticated local HTTP service rejected unauthorized requests and returned
actual configured capability and completed neural-job records. No specific
Google Drive account, phone-accessible HTTPS runner, live ChatGPT host or Android
GPU run was available for verification. APK signing/install evidence belongs to
the independent original-signer CI artifact; source checks cannot replace it.
