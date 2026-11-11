"""Capture a real Rust reaction window preceded by multiple contiguous tile updates."""
from pathlib import Path
import json
import random
from libsatori import arena

game_id = "00000000-0000-0000-0000-000000000063"
users = [f"00000000-0000-4000-8000-{i + 1:012d}" for i in range(4)]
config = {"user_id": users[0], "players": [{"user_id": user, "seat": i} for i, user in enumerate(users)],
          "ai_seats": [], "rules": {"rule_mode": "riichi", "n_players": 4, "game_length_mode": "east"}}
for seed in range(42, 100):
    game = arena.CloudGameSession(game_id, seed, json.dumps(config))
    for seat in range(4):
        game.apply_json(seat, json.dumps({"kind": "ready", "command_id": f"ready-{seat}", "ready": True}))
    game.apply_json(0, json.dumps({"kind": "start", "command_id": "start"}))
    snapshots = [json.loads(game.view_json(0))]
    rng = random.Random(seed)
    for step in range(800):
        for seat in range(4):
            view = json.loads(game.view_json(seat))
            decision = view["decision"]
            if not decision:
                continue
            actions = decision["actions"]
            choice = next((a for kind in ["hora", "pon", "chi", "daiminkan", "reach"]
                           for a in actions if a["type"] == kind), rng.choice(actions))
            game.apply_json(seat, json.dumps({"kind": "action", "command_id": f"action-{step}-{seat}",
                "decision_id": decision["decision_id"], "action_id": choice["action_id"]}))
            current = json.loads(game.view_json(0))
            own = current["decision"]
            if own and own.get("kind") == "reaction" and len(snapshots) >= 3:
                before = snapshots[-3]
                updates = json.loads(game.updates_json(0, before["cursor"]))
                types = [event["type"] for update in updates for event in update.get("events", [])]
                if types.count("dahai") + types.count("tsumo") >= 2:
                    output = Path(__file__).resolve().parents[1] / "src/gametest/resources/decision-animation.json"
                    output.write_text(json.dumps({"seed": seed, "before": before, "updates": updates},
                                                ensure_ascii=False, separators=(",", ":")) + "\n")
                    print(f"Captured reaction after {types}, seed {seed}")
                    raise SystemExit(0)
            snapshots.append(current)
        if snapshots[-1]["status"] == "finished":
            break
raise RuntimeError("No reaction window with preceding tile animations found")
