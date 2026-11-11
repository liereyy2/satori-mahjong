"""Export actual Rust snapshots and settlements for offline Minecraft rendering tests."""
from pathlib import Path
import json
from libsatori import arena

GAME = "00000000-0000-0000-0000-0000000002bd"
CONFIG = {"user_id": "00000000-0000-0000-0000-000000000001", "managed": True,
          "players": [{"user_id": f"00000000-0000-0000-0000-{s+10:012d}", "name": f"Player{s}", "seat": s} for s in range(4)],
          "ai_seats": [], "rules": {"rule_mode": "sichuan", "n_players": 4, "game_length_mode": "4"}}


def capture() -> dict:
    cases = {}
    for seed in range(20):
        game = arena.CloudGameSession(GAME, seed, json.dumps(CONFIG))
        game.apply_json(4, json.dumps({"kind": "start", "command_id": "start"}))
        for step in range(1800):
            view = json.loads(game.view_json(0))
            decision = view["decision"]
            if decision and decision["kind"] in ("dingque", "exchange_three"):
                cases.setdefault(decision["kind"], view)
            if all(value is not None for value in view["public"]["sichuan"]["void_suits"]):
                cases.setdefault("playing", view)
            presentation = json.loads(game.presentation_json(0))
            if view["display"]["standing_hint"] and view["display"]["standing_hint"]["shanten"] == 0:
                cases.setdefault("ready", {"view": view, "presentation": presentation})
            settlement = presentation["settlement"]
            if settlement and any(entry["kind"] == "hora" for entry in settlement["entries"]):
                cases.setdefault("win", {"view": view, "presentation": presentation})
            if view["status"] == "playing" and not settlement and any(view["public"]["sichuan"]["won"]):
                cases.setdefault("won", view)
            if settlement and any(entry["kind"] == "ryukyoku" and any(entry["tenpai"]) and entry["sichuan"]["transfers"] for entry in settlement["entries"]):
                cases.setdefault("draw", {"view": view, "presentation": presentation})
            if view["status"] == "finished":
                cases.setdefault("match", {"view": view, "presentation": presentation,
                                           "result": json.loads(game.result_json())})
                break
            for seat in range(4):
                state = json.loads(game.view_json(seat))
                decision = state["decision"]
                if decision is None:
                    continue
                actions = decision["actions"]
                wins = [a for a in actions if a["type"] == "hora"]
                passes = [a for a in actions if a["type"] == "pass"]
                chosen = (wins or passes or actions)[0]
                game.apply_json(seat, json.dumps({"kind": "action", "command_id": f"a-{step}-{seat}",
                    "decision_id": decision["decision_id"], "action_id": chosen["action_id"]}))
        if all(name in cases for name in ("exchange_three", "dingque", "playing", "won", "ready", "win", "draw", "match")):
            return cases
    raise AssertionError("Missing real Sichuan test projections: " + str(cases.keys()))


if __name__ == "__main__":
    directory = Path(__file__).resolve().parents[1] / "src/gametest/resources"
    cases = capture()
    # Keep ordinary world views together and larger opening/settlement payloads separate.
    groups = {"sichuan-examples": {name: cases[name] for name in ("dingque", "playing", "won")},
              "sichuan-exchange": cases["exchange_three"], "sichuan-draw": cases["draw"],
              "sichuan-ready": cases["ready"],
              "sichuan-win": cases["win"],
              "sichuan-match": cases["match"]}
    for name, data in groups.items():
        text = json.dumps(data, ensure_ascii=False, indent=2) + "\n"
        assert len(text.splitlines()) < 3000, name
        (directory / (name + ".json")).write_text(text)
    print("Exported exchange, dingque, play, concealed winner, draw and match from Rust")
