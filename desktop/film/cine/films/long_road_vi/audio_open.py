"""The cold open's sound, laid before the film's own: a low held chord, a slow pulse, the spoken line (voice_open.wav,
made with VolTune: voice.say), a rise into the cut. Reads audio_film.wav (what audio.py wrote), writes audio.wav."""
import numpy as np, os, sys
from scipy.io import wavfile
from scipy.signal import butter, sosfilt, fftconvolve
F = os.environ.get('CINE_FILM') or os.getcwd(); SR = 44100; OPEN = 10.0; R = np.random.RandomState(9)
sr, film = wavfile.read(os.path.join(F, 'audio_film.wav')); film = film.astype(np.float32) / 32768; assert sr == SR
sr, v = wavfile.read(os.path.join(F, 'voice_open.wav')); v = v.astype(np.float32) / 32768; assert sr == SR
lp = lambda x, fc: sosfilt(butter(2, fc / (SR / 2), output='sos'), x); hp = lambda x, fc: sosfilt(butter(2, fc / (SR / 2), 'high', output='sos'), x)
n = int(OPEN * SR); t = np.arange(n) / SR; o = np.zeros((n, 2), np.float32)
f = lambda m: 440 * 2 ** ((m - 69) / 12)
pad = sum(lp(2 * ((f(m) * (1 + d) * t + R.rand()) % 1) - 1, 520) for m in (38, 45, 50, 57) for d in (-0.006, 0.006)) / 8
env = np.clip(t / 1.5, 0, 1) * np.clip((8.3 - t) / 1.2, 0, 1); o += (pad * env * 0.42)[:, None]
for k in range(7):   # a slow pulse, like a heart
    tt = 0.9 + k * 1.02
    for d, g in ((0, 0.5), (0.24, 0.34)):
        m = int(0.3 * SR); x = np.arange(m) / SR; b = np.sin(2 * np.pi * (46 * x + 30 * (1 - np.exp(-x * 30)) / 30 * 3)) * np.exp(-x * 13) * g; i = int((tt + d) * SR); o[i:i + m] += b[:n - i, None]
v = hp(v, 85); v = v / (np.abs(v).max() + 1e-9) * 0.82; ir = R.randn(int(1.6 * SR)) * np.exp(-np.arange(int(1.6 * SR)) / SR * 3.6) * 0.02; vr = v + fftconvolve(v, ir)[:len(v)] * 0.55
i = int(1.0 * SR); o[i:i + len(vr)] += vr[:n - i, None]
m = int(1.9 * SR); x = np.arange(m) / SR; rise = hp(R.randn(m), 600) * (x / 1.9) ** 3 * 0.16 + np.sin(2 * np.pi * np.cumsum(120 * (4 ** (x / 1.9))) / SR) * (x / 1.9) ** 2 * 0.05; i = int(8.1 * SR); o[i:i + m] += rise[:, None]
o[-int(0.02 * SR):] *= np.linspace(1, 0, int(0.02 * SR))[:, None]
mix = np.concatenate([np.tanh(o * 1.1) * 0.8, film]); wavfile.write(os.path.join(F, 'audio.wav'), SR, (np.clip(mix, -1, 1) * 32767).astype(np.int16)); print('written', len(mix) / SR, 's')
