"""Check the shipped static rulebook hands with the existing Rust scoring engine."""
from __future__ import annotations

import json
from collections import Counter
from pathlib import Path

import libsatori

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'src/main/resources/assets/satori_mahjong/help/riichi_examples.json'
ADDITIONAL = {
    'riichi': ('立直', 1), 'double_riichi': ('両立直', 2), 'ippatsu': ('一発', 1),
    'menzen_tsumo': ('門前清自摸和', 1), 'haitei': ('海底摸月', 1),
    'houtei': ('河底撈魚', 1), 'rinshan': ('嶺上開花', 1), 'chankan': ('槍槓', 1),
}


def deaka(tile: int) -> int:
    return (4, 13, 22)[tile - 34] if tile >= 34 else tile


def mjai_tile(tile: int) -> str:
    if tile >= 34:
        return ('5mr', '5pr', '5sr')[tile - 34]
    return f'{tile % 9 + 1}{"mps"[tile // 9]}' if tile < 27 else 'ESWNPFC'[tile - 27]


def first_draw_score(example: dict) -> dict:
    hand = [t for g in example['groups'] for t in g['tiles']]
    winning = example['groups'][example['winGroup']]['tiles'][example['winIndex']]
    hand.remove(winning)
    state = libsatori.state.PlayerState(0)
    dealer = example['seatWind'] == 27
    events = [{'type': 'start_kyoku', 'bakaze': 'E', 'dora_marker': '8p', 'kyoku': 1,
               'honba': 0, 'kyotaku': 0, 'oya': 0 if dealer else 3,
               'scores': [25000] * 4, 'tehais': [[mjai_tile(t) for t in hand]] + [['?'] * 13] * 3}]
    if not dealer:
        events += [{'type': 'tsumo', 'actor': 3, 'pai': '?'},
                   {'type': 'dahai', 'actor': 3, 'pai': '7p', 'tsumogiri': True}]
    events += [{'type': 'tsumo', 'actor': 0, 'pai': mjai_tile(winning)}]
    for event in events:
        state.update(json.dumps(event))
    return state.agari_result_detail(False)


def verify(example: dict) -> dict:
    groups = example['groups']
    physical = [t for g in groups for t in g['tiles']]
    assert all(0 <= t <= 36 for t in physical), example['id']
    assert max(Counter(map(deaka, physical)).values()) <= 4, example['id']
    assert len(physical) - sum(g['kind'] in ('ankan', 'minkan') for g in groups) == 14, example['id']
    assert all(physical.count(t) <= 1 for t in (34, 35, 36)), example['id']
    winning = groups[example['winGroup']]['tiles'][example['winIndex']]
    assert groups[example['winGroup']]['kind'] == 'hand', example['id']
    if 'first_draw' in example['context']:
        score = first_draw_score(example)
    else:
        counts = [0] * 34
        melds = {kind: [] for kind in ('chi', 'pon', 'minkan', 'ankan')}
        for group in groups:
            tiles = list(map(deaka, group['tiles']))
            if group['kind'] == 'hand':
                for tile in tiles:
                    counts[tile] += 1
            else:
                melds[group['kind']].append(min(tiles))
        score = libsatori.compute_agari_score(
            counts, melds['chi'], melds['pon'], melds['minkan'], melds['ankan'],
            example['roundWind'], example['seatWind'], deaka(winning),
            example['winMethod'] == 'ron', [ADDITIONAL[c] for c in example['context']],
            0, sum(t >= 34 for t in physical), 0, example['seatWind'] == 27,
        )
        assert score['kind'] != 'no_yaku', example['id']
    names = {y['name'] for y in score['yakus']}
    assert set(example['expected']) <= names, (example['id'], example['expected'], score)
    if 'expectedHan' in example:
        assert score['han'] == example['expectedHan'], score
    if 'expectedYakumanCount' in example:
        assert score['count'] == example['expectedYakumanCount'], score
    return {'id': example['id'], 'yakus': sorted(names), 'score': score}


def main() -> None:
    entries = json.loads(SOURCE.read_text())
    results = [verify(e) for examples in entries.values() for e in examples]
    assert len(results) == 41 and len({e['id'] for e in results}) == 41
    for result in results:
        print(f"{result['id']}: {', '.join(result['yakus'])}")
    print(f'{len(results)} shipped examples verified by Rust, including first-draw event replay.')


if __name__ == '__main__':
    main()
