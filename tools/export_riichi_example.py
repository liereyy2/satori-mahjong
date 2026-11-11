"""Find a called riichi declaration through legal actions and Rust discard hints only."""
import json
import random
from libsatori import arena


def capture_riichi_replacement(game_id: str, config: dict) -> dict:
    for seed in range(32):
        game = arena.CloudGameSession(game_id, seed, json.dumps(config))
        rng = random.Random(seed)
        for seat in range(4):
            game.apply_json(seat, json.dumps({"kind": "ready", "command_id": f"ready-{seat}", "ready": True}))
        game.apply_json(0, json.dumps({"kind": "start", "command_id": "start"}))
        before = json.loads(game.view_json(0))
        for step in range(1200):
            for update in json.loads(game.updates_json(0, before["cursor"])):
                for seat, river in enumerate(update["view"]["public"]["discards"]):
                    if (river and river[-1]["is_riichi_marker"] and not river[-1]["was_called"]
                            and any(d["is_riichi_marker"] and d["was_called"] for d in river[:-1])):
                        checkpoint = game.checkpoint_json()
                        restored = arena.CloudGameSession.restore(checkpoint)
                        assert restored.checkpoint_json() == checkpoint
                        for viewer in range(4):
                            assert restored.view_json(viewer) == game.view_json(viewer)
                        print("Captured riichi_replacement seed", seed, "seat", seat, flush=True)
                        return {"seed": seed, "before": before, "update": update}
                before = update["view"]
            if before["status"] == "finished":
                break
            for seat in range(4):
                view = json.loads(game.view_json(seat))
                decision = view["decision"]
                if decision is None:
                    continue
                actions = decision["actions"]
                # Prefer a legal call specifically when the discard is a visible riichi marker.
                calls = [a for a in actions if a["type"] in {"chi", "pon", "daiminkan"}
                         and view["public"]["discards"][a["target_seat"]][-1]["is_riichi_marker"]]
                reaches = [a for a in actions if a["type"] == "reach"]
                discards = [a for a in actions if a["type"] == "dahai"]
                if calls:
                    choice = calls[0]
                elif reaches:
                    choice = reaches[0]
                elif discards:
                    display = json.loads(game.presentation_json(seat))
                    shanten = {x["tile_id"]: x["hint"]["shanten"] for x in display["discard_hints"]}
                    slots = {x["slot_id"]: x["tile_id"] for x in view["private"]["hand"]}
                    ranks = {a["action_id"]: shanten[slots[a["consume_slot_ids"][0]]] for a in discards}
                    best = min(ranks.values())
                    choice = rng.choice([a for a in discards if ranks[a["action_id"]] == best])
                else:
                    choice = next(a for kind in ["pass", "continue", "hora"] for a in actions if a["type"] == kind)
                game.apply_json(seat, json.dumps({"kind": "action", "command_id": f"step-{step}-{seat}",
                        "decision_id": decision["decision_id"], "action_id": choice["action_id"]}))
    raise AssertionError("No legal called-riichi replacement found in the deterministic seed range")
