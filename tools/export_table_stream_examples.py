"""Export real Rust table projections and contiguous frames for spectator tests."""
import json
from pathlib import Path
from libsatori import arena


def capture() -> dict:
    game_id = "00000000-0000-0000-0000-000000000063"
    config = {"user_id": "00000000-0000-4000-8000-000000000001", "managed": True,
              "players": [{"user_id": f"00000000-0000-4000-8000-{seat+1:012d}", "name": f"Player{seat}", "seat": seat}
                          for seat in range(4)], "ai_seats": [],
              "rules": {"rule_mode": "riichi", "n_players": 4, "game_length_mode": "east"}}
    game = arena.CloudGameSession(game_id, 42, json.dumps(config))
    before = json.loads(game.table_render_json())
    game.apply_json(4, json.dumps({"kind": "start", "command_id": "start"}))
    opening = json.loads(game.table_render_json(before["cursor"]))
    cursor = opening["cursor"]
    for step in range(8):
        for seat in range(4):
            decision = json.loads(game.view_json(seat))["decision"]
            if decision is None:
                continue
            actions = decision["actions"]
            action = next((a for kind in ("none", "dahai", "continue") for a in actions if a["type"] == kind), actions[0])
            game.apply_json(seat, json.dumps({"kind": "action", "command_id": f"sample-{step}-{seat}",
                                            "decision_id": decision["decision_id"], "action_id": action["action_id"]}))
    return {"before": before, "opening": opening, "actions": json.loads(game.table_render_json(cursor))}


if __name__ == "__main__":
    output = Path(__file__).resolve().parents[1] / "src/gametest/resources/table-stream-examples.json"
    output.write_text(json.dumps(capture(), ensure_ascii=False, separators=(",", ":")) + "\n")
    print(output)
