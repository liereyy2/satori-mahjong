"""Produce reviewable VOICEVOX call samples without changing bundled mod sounds.

Run with the conda satori Python and voicevox-core 0.17.0. The model and
ONNX Runtime are explicit local inputs; inference uses two CPU threads.
"""

from __future__ import annotations

import argparse
from dataclasses import asdict
from hashlib import sha256
from importlib.metadata import version
from io import BytesIO
import json
from pathlib import Path

import numpy as np
import soundfile as sf
from voicevox_core.blocking import Onnxruntime, OpenJtalk, Synthesizer, VoiceModelFile


from call_voice_synthesis import RATE, inspect_audio, polish, tune_query, write_json
CALLS = {"pon": "ポ'ン", "kan": "カ'ン", "reach": "リ'イチ"}
PROFILES = {
    "a_light": {
        "label": "A · 轻快",
        "style_id": 2,
        "style": "ノーマル",
        "intonation": 0.90,
        "pitch": -0.015,
        "phoneme_seconds": {"pon": 0.30, "kan": 0.31, "reach": 0.43},
    },
    "b_soft": {
        "label": "B · 柔和",
        "style_id": 0,
        "style": "あまあま",
        "intonation": 0.75,
        "pitch": -0.025,
        "phoneme_seconds": {"pon": 0.34, "kan": 0.35, "reach": 0.48},
    },
}
CREDITS = """VOICEVOX:四国めたん

Synthesized Japanese call samples for SatoriMahjong, 20261016a.
These are synthesized voices, not recordings of a human performance.
A: ノーマル (style 2); B: あまあま (style 0).
VOICEVOX Core 0.17.0, voicevox_vvm 0.16.4 / 0.vvm.

Use and redistribution of these generated audio files must follow the voice
library terms, retain the credit above, and require subsequent recipients
to follow the same terms. These files are not licensed under the Mei CC BY
license used by the currently released mod.

Voice library terms: https://zunko.jp/con_ongen_kiyaku.html
VOICEVOX terms: https://voicevox.hiroshiba.jp/term/
Model terms: https://github.com/VOICEVOX/voicevox_vvm/releases/tag/0.16.4

Processing: explicit phoneme timing, restrained pitch and intonation,
70 Hz low-frequency attenuation, gentle high-frequency shelf, silence trim,
4 ms fade-in / 12 ms fade-out, active RMS normalization with peak headroom.
No time-stretching, added reverberation, or generated sound-service requests.
"""


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--onnxruntime", type=Path, required=True)
    parser.add_argument("--dictionary", type=Path, required=True)
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if version("voicevox-core") != "0.17.0":
        raise ValueError("This sample recipe requires voicevox-core 0.17.0")
    args.output.mkdir(parents=True, exist_ok=True)
    manifest = {
        "status": "audition_samples_not_shipped",
        "credit": "VOICEVOX:四国めたん",
        "core_version": version("voicevox-core"),
        "numpy_version": version("numpy"),
        "soundfile_version": version("soundfile"),
        "model_sha256": sha256(args.model.read_bytes()).hexdigest(),
        "onnxruntime_sha256": sha256(args.onnxruntime.read_bytes()).hexdigest(),
        "sample_rate": RATE,
        "profiles": PROFILES,
        "calls": {},
        "previews": {},
    }
    runtime = Onnxruntime.load_once(filename=str(args.onnxruntime))
    with Synthesizer(runtime, OpenJtalk(str(args.dictionary)),
                     acceleration_mode="CPU", cpu_num_threads=2) as synth:
        with VoiceModelFile.open(args.model) as model:
            synth.load_voice_model(model)
            manifest["model_metas"] = [asdict(meta) for meta in model.metas]
        for profile_id, profile in PROFILES.items():
            folder = args.output / profile_id
            folder.mkdir(exist_ok=True)
            sequence = [np.zeros(int(RATE * 0.15))]
            for call, kana in CALLS.items():
                query = synth.create_audio_query_from_kana(kana, profile["style_id"])
                original = asdict(query)
                tune_query(query, profile, call)
                write_json(folder / f"{call}.query.json", {"original": original, "tuned": asdict(query)})
                raw = synth.synthesis(query, profile["style_id"], enable_interrogative_upspeak=False)
                (folder / f"{call}.raw.wav").write_bytes(raw)
                data, rate = sf.read(BytesIO(raw))
                if rate != RATE or data.ndim != 1:
                    raise ValueError("Unexpected synthesis format")
                data = polish(data)
                sf.write(folder / f"{call}.wav", data, RATE, subtype="PCM_16")
                sf.write(folder / f"{call}.ogg", data, RATE, format="OGG", subtype="VORBIS")
                measurements = {ext: inspect_audio(folder / f"{call}.{ext}") for ext in ("wav", "ogg")}
                upper = 0.60 if call == "reach" else 0.45
                if not 0.20 <= measurements["wav"]["seconds"] <= upper:
                    raise ValueError(f"Call outside audition duration: {profile_id}/{call}")
                manifest["calls"][f"{profile_id}/{call}"] = measurements
                print(profile_id, call, measurements["wav"], flush=True)
                sequence.extend((data, np.zeros(int(RATE * 0.65))))
            preview = np.concatenate(sequence)
            sf.write(args.output / f"{profile_id}.wav", preview, RATE, subtype="PCM_16")
            sf.write(args.output / f"{profile_id}.mp3", preview, RATE, format="MP3")
            manifest["previews"][profile_id] = {
                "order": list(CALLS),
                "gap_seconds": 0.65,
                "wav": inspect_audio(args.output / f"{profile_id}.wav"),
                "mp3": inspect_audio(args.output / f"{profile_id}.mp3"),
            }
    (args.output / "CREDITS.txt").write_text(CREDITS)
    write_json(args.output / "manifest.json", manifest)


if __name__ == "__main__":
    main()
