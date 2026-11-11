"""Shared offline VOICEVOX phoneme timing and audio finishing for Mahjong calls."""

from __future__ import annotations
from hashlib import sha256
import json
from pathlib import Path
import numpy as np
import soundfile as sf

RATE = 24000

def write_json(path: Path, value: object) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n")


def tune_query(query, profile: dict, call: str) -> None:
    """Keep consonant articulation, and budget vowel time before synthesis."""
    if len(query.accent_phrases) != 1:
        raise ValueError(f"Expected one accent phrase for {call}")
    phrase = query.accent_phrases[0]
    phrase.pause_mora = None
    consonant_time = 0.0
    for mora in phrase.moras:
        if mora.consonant_length is not None:
            mora.consonant_length = float(np.clip(mora.consonant_length, 0.025, 0.065))
            consonant_time += mora.consonant_length
    vowel_budget = profile["phoneme_seconds"][call] - consonant_time
    if vowel_budget <= 0:
        raise ValueError(f"No vowel time left for {call}")
    # Nasal endings stay short; the long vowel in リーチ stays intact.
    weights = np.array([m.vowel_length for m in phrase.moras])
    if call in ("pon", "kan"):
        weights[-1] *= 0.75
    for mora, length in zip(phrase.moras, weights / weights.sum() * vowel_budget):
        mora.vowel_length = float(length)
    query.speed_scale = 1.0
    query.pitch_scale = profile["pitch"]
    query.intonation_scale = profile["intonation"]
    query.volume_scale = 1.0
    query.pre_phoneme_length = 0.015
    query.post_phoneme_length = 0.035
    query.output_sampling_rate = RATE
    query.output_stereo = False
    query.validate()


def active_bounds(data: np.ndarray, fraction: float) -> tuple[int, int]:
    active = np.flatnonzero(np.abs(data) > np.max(np.abs(data)) * fraction)
    if not len(active):
        raise ValueError("Synthesized audio is silent")
    return int(active[0]), int(active[-1]) + 1


def polish(data: np.ndarray) -> np.ndarray:
    start, stop = active_bounds(data, 0.001)
    data = data[max(0, start - int(RATE * 0.012)):stop + int(RATE * 0.025)]
    # Pad FFT filtering to avoid circular wraparound into the opening consonant.
    padding = int(RATE * 0.03)
    padded = np.pad(data, padding)
    frequencies = np.fft.rfftfreq(len(padded), 1 / RATE)
    low_cut = frequencies / np.sqrt(frequencies ** 2 + 70 ** 2)
    high_shelf = 0.8 + 0.2 / (1 + (frequencies / 5200) ** 4)
    filtered = np.fft.irfft(np.fft.rfft(padded) * low_cut * high_shelf, n=len(padded))
    data = filtered[padding:-padding]
    attack, release = int(RATE * 0.004), int(RATE * 0.012)
    data[:attack] *= np.linspace(0, 1, attack)
    data[-release:] *= np.linspace(1, 0, release)
    start, stop = active_bounds(data, 0.01)
    rms = np.sqrt(np.mean(data[start:stop] ** 2))
    gain = min(10 ** (-18 / 20) / rms, 0.78 / np.max(np.abs(data)))
    return data * gain


def inspect_audio(path: Path) -> dict:
    data, rate = sf.read(path)
    if rate != RATE or data.ndim != 1 or not np.all(np.isfinite(data)):
        raise ValueError(f"Invalid audio format: {path}")
    start, stop = active_bounds(data, 0.01)
    peak = float(np.max(np.abs(data)))
    if not 0.01 < peak < 0.90:
        raise ValueError(f"Invalid peak {peak}: {path}")
    return {
        "seconds": round(len(data) / rate, 4),
        "peak": round(peak, 5),
        "active_rms_dbfs": round(float(20 * np.log10(np.sqrt(np.mean(data[start:stop] ** 2)))), 2),
        "leading_ms_at_minus_40db": round(start / rate * 1000, 1),
        "trailing_ms_at_minus_40db": round((len(data) - stop) / rate * 1000, 1),
        "sha256": sha256(path.read_bytes()).hexdigest(),
    }
