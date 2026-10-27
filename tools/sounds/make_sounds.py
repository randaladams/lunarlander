"""Synthesizes the game's sound effects into app/src/main/res/raw/.

Run from the repo root:  python3 tools/sounds/make_sounds.py
Needs numpy. Everything is generated, so there are no licensing questions.
"""
import os
import wave

import numpy as np

RATE = 22050
OUT = os.path.join("app", "src", "main", "res", "raw")
rng = np.random.default_rng(1979)


def save(name, x, peak=0.9):
    x = np.asarray(x, dtype=np.float64)
    x = x / (np.max(np.abs(x)) + 1e-9) * peak
    data = (x * 32767).astype("<i2").tobytes()
    os.makedirs(OUT, exist_ok=True)
    with wave.open(os.path.join(OUT, name + ".wav"), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(data)
    print(f"{name}.wav  {len(x) / RATE:.2f}s")


def shaped_noise(n, lo, hi, tilt=1.0):
    """Noise with energy between lo and hi Hz. Built in the frequency domain,
    so it's periodic: the end joins the start seamlessly (perfect for loops)."""
    freqs = np.fft.rfftfreq(n, 1 / RATE)
    spec = rng.normal(size=len(freqs)) + 1j * rng.normal(size=len(freqs))
    band = ((freqs > lo) & (freqs < hi)).astype(float)
    weight = band / np.maximum(freqs, 1.0) ** (0.5 * tilt)
    return np.fft.irfft(spec * weight, n)


# --- Thrust: a seamless 1 s rocket rumble with a gentle flutter.
n = RATE
t = np.arange(n) / RATE
rumble = shaped_noise(n, 40, 900, tilt=1.2)
hiss = shaped_noise(n, 900, 4000, tilt=0.5) * 0.18
flutter = 1 + 0.12 * np.sin(2 * np.pi * 9 * t)  # 9 whole cycles per loop: seamless
save("thrust", (rumble + hiss * np.max(np.abs(rumble)) / (np.max(np.abs(hiss)) + 1e-9) * 0.18) * flutter, peak=0.7)

# --- Crash: a hard thump plus a decaying, darkening noise burst.
dur = 1.6
n = int(RATE * dur)
t = np.arange(n) / RATE
noise = rng.normal(size=n)
# Time-varying low-pass: one-pole filter whose cutoff falls from ~5 kHz to ~150 Hz.
cut = 5000 * np.exp(-t * 3.2) + 150
a = np.exp(-2 * np.pi * cut / RATE)
y = np.zeros(n)
acc = 0.0
for i in range(n):
    acc = a[i] * acc + (1 - a[i]) * noise[i]
    y[i] = acc
y *= np.exp(-t * 2.4)
thump = np.sin(2 * np.pi * (70 * t - 25 * t * t)) * np.exp(-t * 7)
attack = np.minimum(1, t / 0.004)
crash = (y / np.max(np.abs(y)) + 0.8 * thump) * attack
save("crash", crash, peak=0.95)

# --- Shield: a rising, warbling energy zap.
dur = 0.5
n = int(RATE * dur)
t = np.arange(n) / RATE
f = 260 + 1100 * (t / dur) ** 0.7
phase = 2 * np.pi * np.cumsum(f) / RATE
tone = np.sign(np.sin(phase)) * 0.35 + np.sin(phase) * 0.65   # part square: arcade bite
tone += 0.4 * np.sin(phase * 1.5)
warble = 0.6 + 0.4 * np.sin(2 * np.pi * 28 * t)
env = np.minimum(1, t / 0.01) * np.exp(-t * 4.5)
save("shield", tone * warble * env, peak=0.75)

# --- Warning: a short two-tone alert (played once per incoming meteor).
def beep(freq, dur):
    n = int(RATE * dur)
    t = np.arange(n) / RATE
    ph = 2 * np.pi * freq * t
    tone = np.sign(np.sin(ph)) * 0.45 + np.sin(ph) * 0.55
    env = np.minimum(1, t / 0.005) * np.minimum(1, (dur - t) / 0.015)
    return tone * env

gap = np.zeros(int(RATE * 0.045))
save("warning", np.concatenate([beep(1180, 0.085), gap, beep(880, 0.11)]), peak=0.6)
