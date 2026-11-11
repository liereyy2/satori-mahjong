"""Capture consecutive authorized Rust frames from tenpai through the next own discard."""
from pathlib import Path
import json
import random
from libsatori import arena


def capture(mode: str) -> dict:
    config = {"user_id": "owner", "managed": True,
              "players": [{"user_id": f"p{seat}", "name": f"Player{seat}", "seat": seat} for seat in range(4)],
              "ai_seats": [], "rules": {"rule_mode": mode, "n_players": 4,
                                         "game_length_mode": "east" if mode == "riichi" else "4"}}
    for seed in range(64):
        game = arena.CloudGameSession("00000000-0000-0000-0000-000000000063", seed, json.dumps(config))
        game.apply_json(4, json.dumps({"kind": "start", "command_id": "start"}))
        rng = random.Random(seed)
        captured = None
        drawn = False
        for step in range(1500):
            for seat in range(4):
                view = json.loads(game.view_json(seat))
                decision = view["decision"]
                if not decision or decision["kind"] == "confirm_kyoku":
                    continue
                actions = decision["actions"]
                display = json.loads(game.presentation_json(seat))
                hints = {hint["tile_id"]: hint["hint"] for hint in display["discard_hints"]}
                # Actions use MJAI tile names; match the server's numeric hint IDs without rules.
                tiles = [f"{n}{s}" for s in "mps" for n in range(1, 10)] + ["E", "S", "W", "N", "P", "F", "C", "5mr", "5pr", "5sr"]
                ready = [a for a in actions if a["type"] == "dahai" and
                         (hints.get(tiles.index(a["event"]["pai"])) or {}).get("shanten") == 0]
                if seat == 0 and captured and drawn and decision["kind"] == "turn":
                    changed = [a for a in actions if a["type"] == "dahai" and a not in ready]
                    if changed:
                        choice = changed[0]
                    else:
                        captured = None
                        choice = rng.choice(actions)
                else:
                    choice = next((a for kind in ("none", "pass", "hora", "pon", "chi", "ankan", "kakan")
                                   for a in actions if a["type"] == kind), None)
                    choice = choice or (ready[0] if ready else rng.choice(actions))
                before = json.loads(game.view_json(0))
                game.apply_json(seat, json.dumps({"kind": "action", "command_id": f"a-{step}-{seat}",
                    "decision_id": decision["decision_id"], "action_id": choice["action_id"]}))
                current = json.loads(game.view_json(0))
                hint = current["display"]["standing_hint"]
                if captured:
                    captured["updates"].extend(json.loads(game.updates_json(0, before["cursor"])))
                    if current["display"]["drawn_seats"][0] and hint == captured["before"]["display"]["standing_hint"]:
                        drawn = True
                    if drawn and hint != captured["before"]["display"]["standing_hint"]:
                        if seat == 0 and choice["type"] == "dahai" and current["status"] == "playing":
                            assert len(captured["updates"]) > 2
                            return captured
                        captured = None
                if not captured and hint and hint["shanten"] == 0 and not current["display"]["drawn_seats"][0]:
                    captured = {"seed": seed, "before": current, "updates": []}
                    drawn = False
            if json.loads(game.view_json(0))["status"] == "finished" or any(
                (json.loads(game.view_json(s))["decision"] or {}).get("kind") == "confirm_kyoku" for s in range(4)
            ):
                break
    raise AssertionError(f"Missing {mode} hint lifecycle")


if __name__ == "__main__":
    output = Path(__file__).resolve().parents[1] / "src/gametest/resources/ready-hint-lifecycle.json"
    output.write_text(json.dumps({mode: capture(mode) for mode in ("riichi", "sichuan")},
                                ensure_ascii=False, separators=(",", ":")) + "\n")
    print("Captured real Riichi and Sichuan waits across opponent turns, own draw and own discard")
