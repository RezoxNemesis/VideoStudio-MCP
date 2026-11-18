# VolTune Studio voice bank

Short clips of real people reading aloud. VolTune Studio (Chatterbox Multilingual V3) takes the sound of a voice
from one of these clips, so studio voices sound like people and not like a synthesiser
(`src/voice/engine/studio_bank.ts` chooses the reader for each VolTune voice).

| Clip | Reader | Source | Licence |
| --- | --- | --- | --- |
| hi_f_dc.wav, hi_f_dc2.wav | dc | *Panchatantra* (Hindi), LibriVox | Public domain |
| hi_m_deep.wav | reader of section 41 | *Panchatantra* (Hindi), LibriVox | Public domain |
| hi_f_pratibha.wav, hi_f_pratibha2.wav | pratibhanair | First Chapter Collection 010, *Kshama*, LibriVox | Public domain |
| hi_m_shashank.wav, hi_m_shashank2.wav | Shashank Jakhmola | Multilingual Short Works 024, UDHR in Hindi, LibriVox | Public domain |
| en_m_1188.wav, en_m_7127.wav, en_m_8224.wav, en_f_4446.wav, en_f_237.wav, en_f_4992.wav, en_f_5683.wav | LibriTTS-R speakers 1188, 7127, 8224, 4446, 237, 4992, 5683 | LibriTTS-R test-clean (Koizumi et al., 2023; from LibriVox via LibriTTS) | CC BY 4.0 |

LibriTTS-R: Y. Koizumi, H. Zen, S. Karita, Y. Ding, K. Yatabe, N. Morioka, M. Bacchiani, Y. Zhang, W. Han, A. Bapna,
"LibriTTS-R: A Restored Multi-Speaker Text-to-Speech Corpus", Interspeech 2023. https://www.openslr.org/141/ —
licensed under CC BY 4.0 (https://creativecommons.org/licenses/by/4.0/). The clips were cut, high-passed at 70 Hz and
loudness-normalised.

LibriVox recordings are in the public domain (https://librivox.org/pages/public-domain/).

The model: Chatterbox Multilingual V3 by Resemble AI (MIT), as the ONNX export Masterx/chatterbox-multilingual-v3-ONNX (MIT).
