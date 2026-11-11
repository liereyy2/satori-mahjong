"""Generate all seven bundled light calls with the approved VOICEVOX voice."""
from __future__ import annotations

import argparse
from dataclasses import asdict
from hashlib import sha256
from importlib.metadata import version
from io import BytesIO
from pathlib import Path
import soundfile as sf
from voicevox_core.blocking import Onnxruntime, OpenJtalk, Synthesizer, VoiceModelFile
from call_voice_synthesis import RATE, inspect_audio, polish, tune_query, write_json

CALLS = {
    "chi": "チ'イ", "pon": "ポ'ン", "kan": "カ'ン", "reach": "リ'イチ",
    "ron": "ロ'ン", "tsumo": "ツ'モ", "ryukyoku": "リュ'ウキョク",
}
LIGHT = {
    "style_id": 2, "style": "ノーマル", "intonation": 0.90, "pitch": -0.015,
    "phoneme_seconds": {
        "chi": 0.31, "pon": 0.30, "kan": 0.31, "reach": 0.43,
        "ron": 0.30, "tsumo": 0.40, "ryukyoku": 0.66,
    },
}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--onnxruntime", type=Path, required=True)
    parser.add_argument("--dictionary", type=Path, required=True)
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--model-terms", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()
    if version("voicevox-core") != "0.17.0":
        raise ValueError("Approved voice recipe requires voicevox-core 0.17.0")
    root = Path(__file__).resolve().parents[1] / "src/main/resources/assets/satori_mahjong"
    output = root / "sounds/calls"
    output.mkdir(parents=True, exist_ok=True)
    args.report.mkdir(parents=True, exist_ok=True)
    manifest = {
        "credit": "VOICEVOX:四国めたん", "profile": LIGHT,
        "core_version": version("voicevox-core"),
        "model_sha256": sha256(args.model.read_bytes()).hexdigest(), "calls": {},
    }
    runtime = Onnxruntime.load_once(filename=str(args.onnxruntime))
    with Synthesizer(runtime, OpenJtalk(str(args.dictionary)),
                     acceleration_mode="CPU", cpu_num_threads=2) as synth:
        with VoiceModelFile.open(args.model) as model:
            synth.load_voice_model(model)
        for name, kana in CALLS.items():
            query = synth.create_audio_query_from_kana(kana, LIGHT["style_id"])
            tune_query(query, LIGHT, name)
            write_json(args.report / f"{name}.query.json", asdict(query))
            raw = synth.synthesis(query, LIGHT["style_id"], enable_interrogative_upspeak=False)
            data, rate = sf.read(BytesIO(raw))
            if rate != RATE:
                raise ValueError("Unexpected synthesis sample rate")
            data = polish(data)
            sf.write(args.report / f"{name}.wav", data, RATE, subtype="PCM_16")
            sf.write(output / f"{name}.ogg", data, RATE, format="OGG", subtype="VORBIS")
            measured = inspect_audio(output / f"{name}.ogg")
            maximum = {"reach": .55, "tsumo": .55, "ryukyoku": .80}.get(name, .42)
            if not .20 < measured["seconds"] < maximum:
                raise ValueError(f"Unexpected duration for {name}: {measured}")
            manifest["calls"][name] = measured
            print(name, measured, flush=True)
    write_json(root / "sounds.json", {"call." + name: {
        "subtitle": "satori.call." + name,
        "sounds": [{"name": "satori_mahjong:calls/" + name, "preload": True}],
    } for name in CALLS})
    (output / "credits.txt").write_text(
        "VOICEVOX:四国めたん\n"
        "SatoriMahjong Japanese calls: chi, pon, kan, reach, ron, tsumo, ryukyoku.\n"
        "Synthesized with VOICEVOX Core 0.17.0, voicevox_vvm 0.16.4 / 0.vvm, normal style 2.\n"
        "Approved light profile: natural speed, individual phoneme timing, restrained pitch,\n"
        "gentle frequency shaping, short fades and active RMS normalization.\n"
        "Generated audio use and redistribution must follow the voice library terms, retain\n"
        "the above credit, and require subsequent recipients to follow these same terms.\n"
        "https://zunko.jp/con_ongen_kiyaku.html\nhttps://voicevox.hiroshiba.jp/term/\n"
        "No endorsement by the voice authors is implied.\n"
    )
    (output / "license-voicevox.txt").write_bytes(args.model_terms.read_bytes())
    write_json(args.report / "manifest.json", manifest)


if __name__ == "__main__":
    main()
