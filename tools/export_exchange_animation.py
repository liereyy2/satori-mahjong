"""Capture real Rust exchange events and role-filtered endpoints for animation checks."""
from pathlib import Path
import json

from libsatori import arena
from export_sichuan_examples import CONFIG, GAME


def capture() -> None:
    output = Path(__file__).resolve().parents[1] / "src/gametest/resources"
    captured = set()
    for seed in range(30):
        game = arena.CloudGameSession(GAME, seed, json.dumps(CONFIG))
        game.apply_json(4, json.dumps({"kind": "start", "command_id": "start"}))
        before = json.loads(game.table_render_json(None))
        for seat in range(4):
            view = json.loads(game.view_json(seat))
            decision = view["decision"]
            choice = decision["actions"][0]
            game.apply_json(seat, json.dumps({"kind": "action", "command_id": f"exchange-{seat}",
                "decision_id": decision["decision_id"], "action_id": choice["action_id"]}))
        frames = json.loads(game.table_render_json(before["cursor"]))["updates"]
        final = frames[-1]
        event = next(e for e in final["events"][4] if e["type"] == "exchange_three_completed")
        direction = event["direction"]
        if direction in captured:
            continue
        previous = frames[-2]["views"] if len(frames) > 1 else before["views"]
        for role, seat in (("player", 0), ("observer", 4), ("public", 5)):
            data = {"seed": seed, "direction": direction, "before": previous[seat],
                    "after": final["views"][seat], "events": final["events"][seat]}
            text = json.dumps(data, ensure_ascii=False, indent=2) + "\n"
            assert len(text.splitlines()) < 3000
            (output / f"sichuan-exchange-motion-{direction}-{role}.json").write_text(text)
        captured.add(direction)
        if len(captured) == 3:
            print("Captured all three directions for player, full-table and public observers")
            return
    raise AssertionError(f"Missing exchange directions: {captured}")


if __name__ == "__main__":
    capture()
