"""Capture legal Rust-session projections and filtered events for offline MC render tests."""
from pathlib import Path
import json
import random
from libsatori import arena
from export_riichi_example import capture_riichi_replacement

GAME = "00000000-0000-0000-0000-000000000063"
USERS = [f"00000000-0000-4000-8000-{i+1:012d}" for i in range(4)]
config = {"user_id": USERS[0], "players": [{"user_id": user, "seat": i} for i, user in enumerate(USERS)],
          "ai_seats": [], "rules": {"rule_mode": "riichi", "n_players": 4, "game_length_mode": "east"}}
cases = {"riichi_replacement": capture_riichi_replacement(GAME, config)}
for seed in range(2048):
    game = arena.CloudGameSession(GAME, seed, json.dumps(config))
    for seat in range(4):
        game.apply_json(seat, json.dumps({"kind":"ready", "command_id":f"ready-{seat}", "ready":True}))
    game.apply_json(0, json.dumps({"kind":"start", "command_id":"start"}))
    view = json.loads(game.view_json(0))
    decision = view["decision"]
    action = next((a for a in decision["actions"] if a["type"] == "ryukyoku"), None) if decision else None
    if action:
        game.apply_json(0, json.dumps({"kind":"action", "command_id":"nine-terminals",
            "decision_id":decision["decision_id"], "action_id":action["action_id"]}))
        cases["settlement_kyushukyuhai"] = {"view":json.loads(game.view_json(0)),
            "presentation":json.loads(game.presentation_json(0))}
        print("Captured settlement_kyushukyuhai seed", seed, flush=True)
        break
assert "settlement_kyushukyuhai" in cases
display_wanted = {"settlement_hora", "settlement_ryukyoku", "tenpai", "no_yaku"}
wanted = {"start_kyoku", "tsumo", "dahai", "chi", "pon", "daiminkan", "ankan", "kakan", "reach", "reach_accepted", "hora", "ryukyoku", "dora"}
hand_wanted = {"own_draw", "own_hand_discard", "own_tsumogiri", "opponent_draw", "opponent_hand_discard", "opponent_tsumogiri"}
wanted |= hand_wanted
wanted.add("ankan_red")
for seed in range(42, 170):
    game = arena.CloudGameSession(GAME, seed, json.dumps(config))
    for seat in range(4):
        game.apply_json(seat, json.dumps({"kind": "ready", "command_id": f"ready-{seat}", "ready": True}))
    before = json.loads(game.view_json(0))
    game.apply_json(0, json.dumps({"kind": "start", "command_id": "start"}))
    rng = random.Random(seed)
    for step in range(1000):
        updates = json.loads(game.updates_json(0, before["cursor"]))
        for update in updates:
            for event in update.get("events", []):
                kind = event["type"]
                if kind in wanted and kind not in cases:
                    cases[kind] = {"seed": seed, "before": before, "update": update}
                    print("Captured", kind, "seed", seed, flush=True)
                if kind == "ankan" and any(tile.endswith("r") for tile in event["consumed"]) and "ankan_red" not in cases:
                    cases["ankan_red"] = {"seed": seed, "before": before, "update": update,
                                          "owner_view": json.loads(game.view_json(event["actor"]))}
                    print("Captured ankan_red seed", seed, flush=True)
                if kind in {"tsumo", "dahai"}:
                    who = "own" if event["actor"] == 0 else "opponent"
                    action = "draw" if kind == "tsumo" else "tsumogiri" if event["tsumogiri"] else "hand_discard"
                    key = f"{who}_{action}"
                    if key not in cases:
                        cases[key] = {"seed": seed, "before": before, "update": update}
                        print("Captured", key, "seed", seed, flush=True)
            before = update["view"]
        if not display_wanted <= cases.keys():
            for viewer in range(4):
                display = json.loads(game.presentation_json(viewer))
                view = json.loads(game.view_json(viewer))
                summary = display["settlement"]
                key = "settlement_" + summary["entries"][0]["kind"] if summary else None
                if key == "settlement_ryukyoku" and (not summary["entries"][0]["hands"] or summary["entries"][0]["reason"] != "exhaustive"):
                    key = None
                if key and key not in cases:
                    cases[key] = {"view": view, "presentation": display}
                    print("Captured", key, "seed", seed, flush=True)
                hint = view["display"]["standing_hint"]
                if hint and hint["shanten"] == 0:
                    key = "no_yaku" if hint["no_yaku"] else "tenpai"
                    if key not in cases:
                        cases[key] = {"view": view, "presentation": display}
                        print("Captured", key, "seed", seed, flush=True)
        if wanted | display_wanted <= cases.keys() or before["status"] == "finished":
            break
        advanced = False
        for seat in range(4):
            view = json.loads(game.view_json(seat))
            decision = view["decision"]
            if decision is None:
                continue
            actions = decision["actions"]
            priorities = ["hora", "ankan", "kakan", "daiminkan", "reach", "pon", "chi"]
            choice = next((a for kind in priorities for a in actions if a["type"] == kind), None)
            if choice is None:
                choice = rng.choice(actions)
            game.apply_json(seat, json.dumps({"kind": "action", "command_id": f"action-{step}-{seat}",
                                             "decision_id": decision["decision_id"], "action_id": choice["action_id"]}))
            advanced = True
        if not advanced:
            raise RuntimeError("Rust session stopped yielding decisions")
    if wanted | display_wanted <= cases.keys():
        break
assert wanted | display_wanted <= cases.keys(), "Missing legal presentation fixtures"
output = Path(__file__).resolve().parents[1] / "src/gametest/resources/electric-examples.json"
created = arena.CloudGameSession(GAME, 42, json.dumps(config))
output.with_name("cloud-created.json").write_text(created.view_json(0) + "\n")
output.write_text(json.dumps(cases, ensure_ascii=False, separators=(",", ":")) + "\n")
output.with_name("cloud-view.json").write_text(json.dumps(cases["own_draw"]["update"]["view"], ensure_ascii=False, separators=(",", ":")) + "\n")
print("Examples:", sorted(cases), "Missing:", sorted(wanted - cases.keys()), flush=True)
