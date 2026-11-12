# Third-party parts of VideoStudio

VideoStudio itself is MIT-licensed (see LICENSE in the repository). It ships with these open parts, each under its own
licence. Nothing here is a paid or closed component.

| Part | What it does in VideoStudio | Licence | Source |
|---|---|---|---|
| eSpeak NG (WebAssembly build, npm `espeak-ng` 1.0.2) | Turns text in German, Russian, Spanish, French, Italian, Portuguese, Dutch, Polish, Swedish, Indonesian, Romanian, Greek and Marathi into speech sounds for the voices | GPL-3.0-or-later (full text: eSpeak-NG-GPL-3.0.txt) | https://github.com/espeak-ng/espeak-ng, packaged by https://github.com/ianmarmour/espeak-ng.js |
| Kokoro-82M voice model and its 41 voice tables | The voices (VolTune) | Apache-2.0 | https://huggingface.co/hexgrad/Kokoro-82M |
| kokoro-js | Voice tables and the browser speech engine | Apache-2.0 | https://github.com/hexgrad/kokoro |
| phonemizer (npm) | English speech sounds | Apache-2.0 | https://github.com/xenova/phonemizer.js |
| ONNX Runtime Web | Runs the voice models | MIT | https://github.com/microsoft/onnxruntime |
| SpessaSynth core | The instrument synthesizer | Apache-2.0 | https://github.com/spessasus/spessasynth_core |
| GeneralUser GS SoundFont | 261 sampled instruments, 13 drum kits | GeneralUser GS licence (free for any use; GeneralUser-GS-LICENSE.txt beside the bank) | https://github.com/mrbumpy409/GeneralUser-GS |
| MakeHuman base mesh and rig | The real human bodies | CC0 1.0 (assets) | https://github.com/makehumancommunity/makehuman |
| CMU Graphics Lab Motion Capture Database | The motion-capture performances | Free for any use (CMU) | http://mocap.cs.cmu.edu |
| KayKit packs by Kay Lousberg | The 3D model library | CC0 1.0 | https://github.com/KayKit-Game-Assets |
| FFmpeg (static build) | Reading, writing and encoding media | GPL | https://ffmpeg.org |
| @napi-rs/canvas | Drawing in the film engine | MIT | https://github.com/Brooooooklyn/canvas |

The Studio instruments (808s, supersaws, sitar, tabla …) and the drum kits of the Studio bank are made by VideoStudio's
own code at run time; they contain no samples from anywhere else.

GPL: the source of eSpeak NG and of FFmpeg is at the addresses above; VideoStudio's own source is public in its
repository. You may ask for the exact source of any GPL part shipped with a release and it will be provided.
