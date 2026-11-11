package cn.satori.mahjong.protocol;

import cn.satori.mahjong.protocol.TableScene.Tile;

import com.google.gson.*;

import java.util.*;
import java.util.function.UnaryOperator;

/** Paths consume ordered, privacy-filtered cloud events. Final geometry is always the snapshot. */
final class TableTransition {
    private record Cue(String type, int actor, int target, float start, float duration) {}

    private record Path(
            Tile from,
            Tile to,
            float start,
            float duration,
            float arc,
            boolean overHand,
            boolean direct,
            boolean tidy) {}

    private final List<Tile> target;
    private final List<Path> paths = new ArrayList<>();
    private final List<Tile> removed = new ArrayList<>();
    private final List<Cue> cues = new ArrayList<>();
    private final long start;
    private final float duration;
    private final boolean opening;
    private final TableWall wall;
    private final ExchangeTileMotion exchange;

    TableTransition(
            List<Tile> before, List<Tile> after, JsonArray events, long start, TableWall wall) {
        this.start = start;
        this.wall = wall;
        target = after;
        var exchangeEvent =
                events.asList().stream()
                        .map(JsonElement::getAsJsonObject)
                        .filter(e -> e.get("type").getAsString().equals("exchange_three_completed"))
                        .findFirst();
        exchange = exchangeEvent.map(e -> new ExchangeTileMotion(before, after, e)).orElse(null);
        float time = 0;
        for (JsonElement raw : events) {
            JsonObject event = raw.getAsJsonObject();
            String type = event.get("type").getAsString();
            float length =
                    switch (type) {
                        case "start_kyoku" -> 3.9f;
                        case "exchange_three_completed" -> ExchangeTileMotion.DURATION;
                        case "tsumo", "dahai" -> .144f;
                        case "chi", "pon", "daiminkan", "ankan", "kakan" -> .72f;
                        case "reach", "reach_accepted" -> .42f;
                        case "hora", "ryukyoku" -> .85f;
                        case "dora" -> .4f;
                        case "settle" -> .35f;
                        default -> 0;
                    };
            if (length == 0) continue;
            cues.add(
                    new Cue(
                            type,
                            event.has("actor") ? event.get("actor").getAsInt() : -1,
                            event.has("target") ? event.get("target").getAsInt() : -1,
                            time,
                            length));
            time += length;
        }
        opening = cues.stream().anyMatch(c -> c.type.equals("start_kyoku"));
        if (opening || exchange != null) {
            duration = Math.max(.15f, time);
            return;
        }
        Map<String, Tile> old = new LinkedHashMap<>();
        before.forEach(t -> old.put(t.key(), t));
        TileOrigins allocation = new TileOrigins(before, after, events, wall);
        Set<String> used = allocation.used;
        float end = Math.max(.15f, time);
        for (Tile next : after) {
            Tile origin = allocation.origins.getOrDefault(next.key(), old.get(next.key()));
            Cue cue =
                    forTile(
                            next,
                            next.key().startsWith("hand-")
                                    ? origin != null && origin.key().startsWith("hand-")
                                            ? origin
                                            : null
                                    : old.get(next.key()),
                            end);
            if (origin == null && next.key().startsWith("stick"))
                origin =
                        next.pose(
                                next.x() * 1.5f,
                                next.y() + .06f,
                                next.z() * 1.5f,
                                next.yaw(),
                                next.pitch(),
                                next.face());
            if (origin == null) origin = next;
            used.add(origin.key());
            float distance = Math.abs(origin.x() - next.x()) + Math.abs(origin.z() - next.z());
            boolean slidingRiver =
                    next.key().startsWith("river") && next.key().equals(origin.key());
            boolean handSurvivor =
                    next.key().startsWith("hand-") && origin.key().startsWith("hand-");
            boolean insertDrawn =
                    handSurvivor
                            && origin.key().endsWith("-drawn")
                            && !next.key().endsWith("-drawn")
                            && cue.type.equals("dahai");
            if (handSurvivor && cue.type.equals("dahai"))
                cue = new Cue("tidy", next.seat(), -1, cue.start + cue.duration, .20f);
            if (insertDrawn)
                cue = new Cue("insert", next.seat(), -1, cue.start + cue.duration, .26f);
            end = Math.max(end, cue.start + cue.duration);
            boolean direct = cue.type.equals("tsumo") || cue.type.equals("dahai");
            paths.add(
                    new Path(
                            origin,
                            next,
                            cue.start + (direct ? .012f : 0),
                            direct ? .120f : cue.duration,
                            !direct
                                            && !handSurvivor
                                            && !slidingRiver
                                            && !next.key().equals(origin.key())
                                            && distance > .15f
                                    ? .18f
                                    : 0,
                            insertDrawn
                                    || next.key().startsWith("meld")
                                            && !next.key().equals(origin.key()),
                            direct,
                            cue.type.equals("tidy")));
        }
        for (Tile tile : before)
            if (!used.contains(tile.key()) && !oldKey(after, tile.key())) removed.add(tile);
        duration = end;
    }

    private static boolean oldKey(List<Tile> tiles, String key) {
        return tiles.stream().anyMatch(t -> t.key().equals(key));
    }

    private Cue forTile(Tile tile, Tile origin, float timeline) {
        for (Cue cue : cues) {
            if (tile.key().startsWith("river")
                    && origin != null
                    && cue.target == tile.seat()
                    && Set.of("chi", "pon", "daiminkan").contains(cue.type))
                return new Cue("compact_river", cue.target, -1, cue.start, .24f);
            if (tile.key().startsWith("wall")
                    && tile.face() >= 0
                    && (origin == null || origin.face() != tile.face())
                    && cue.type.equals("dora")) return cue;
            if (cue.actor != tile.seat() && cue.actor != -1) continue;
            if (tile.key().startsWith("river") && origin == null && cue.type.equals("dahai"))
                return cue;
            if (tile.key().startsWith("meld")
                    && Set.of("chi", "pon", "daiminkan", "ankan", "kakan").contains(cue.type))
                return cue;
            if (tile.key().startsWith("stick") && cue.type.startsWith("reach")) return cue;
            if (tile.key().startsWith("hand")
                    && (origin == null ? cue.type.equals("tsumo") : !cue.type.startsWith("reach")))
                return cue;
        }
        return new Cue("settle", -1, -1, 0, timeline);
    }

    long endsAt() {
        return start + (long) (duration * 1_000_000_000L);
    }

    List<Tile> target() {
        return target;
    }

    private float elapsed(long now) {
        return Math.max(0, (now - start) / 1_000_000_000f);
    }

    static float ease(float t) {
        t = Math.clamp(t, 0, 1);
        return t * t * (3 - 2 * t);
    }

    private static float tidyEase(float t) {
        t = Math.clamp(t, 0, 1);
        return t == 1 ? 1 : 1 - (float) Math.pow(2, -10 * t);
    }

    List<TableScene.Sound> sounds() {
        var sounds = new ArrayList<TableScene.Sound>();
        for (Cue cue : cues) {
            String name =
                    switch (cue.type) {
                        case "tsumo" -> "tile.draw";
                        case "dahai" -> "tile.discard";
                        case "chi", "pon", "reach", "ryukyoku" -> "call." + cue.type;
                        case "daiminkan", "ankan", "kakan" -> "call.kan";
                        case "hora" -> "call." + (cue.actor == cue.target ? "tsumo" : "ron");
                        default -> "";
                    };
            if (!name.isEmpty()) {
                float delay = name.startsWith("tile.") ? .132f : 0;
                sounds.add(
                        new TableScene.Sound(
                                0,
                                name,
                                cue.actor,
                                start + Math.round((cue.start + delay) * 1_000_000_000.0)));
            }
        }
        return List.copyOf(sounds);
    }

    float shutter(long now) {
        if (!opening) return 0;
        float t = elapsed(now);
        return ease(t / .55f) * (1 - ease((t - 1.45f) / .35f));
    }

    String cue(long now) {
        float time = elapsed(now);
        for (Cue cue : cues)
            if (time >= cue.start && time < cue.start + cue.duration) return cue.type;
        return "";
    }

    int decisionSeat(long now, int previous) {
        float time = elapsed(now);
        int seat = previous;
        for (Cue cue : cues) {
            if (time < cue.start) break;
            seat =
                    switch (cue.type) {
                        case "tsumo",
                                        "dahai",
                                        "chi",
                                        "pon",
                                        "daiminkan",
                                        "ankan",
                                        "kakan",
                                        "reach" ->
                                cue.actor;
                        case "start_kyoku", "hora", "ryukyoku" -> -1;
                        default -> seat;
                    };
        }
        return seat;
    }

    TableScene.Call call(long now) {
        float time = elapsed(now);
        for (int index = 0; index < cues.size(); index++) {
            Cue cue = cues.get(index);
            if (time < cue.start || time >= cue.start + cue.duration) continue;
            String voice =
                    switch (cue.type) {
                        case "chi", "pon", "reach", "ryukyoku" -> cue.type;
                        case "daiminkan", "ankan", "kakan" -> "kan";
                        case "hora" -> cue.actor == cue.target ? "tsumo" : "ron";
                        default -> "";
                    };
            return voice.isEmpty() ? null : new TableScene.Call(start + index, voice, cue.actor);
        }
        return null;
    }

    List<Tile> sample(long now) {
        return sample(now, UnaryOperator.identity());
    }

    List<Tile> sample(long now, UnaryOperator<Tile> viewTransform) {
        float time = elapsed(now);
        if (time >= duration) return target.stream().map(viewTransform).toList();
        if (opening) return opening(time, viewTransform);
        if (exchange != null) return exchange.sample(time, viewTransform);
        List<Tile> frame = new ArrayList<>();
        for (Path path : paths) {
            float raw = Math.clamp((time - path.start) / path.duration, 0, 1);
            float t = path.direct ? raw : path.tidy ? tidyEase(raw) : ease(raw);
            Tile a = viewTransform.apply(path.from), b = viewTransform.apply(path.to);
            float travel = path.overHand ? ease((raw - .2f) / .6f) : t;
            float y = a.y() + (b.y() - a.y()) * t + (float) Math.sin(raw * Math.PI) * path.arc;
            if (path.overHand) {
                float clearance = Math.max(1.34f, Math.max(a.y(), b.y()) + .20f);
                y =
                        raw < .2f
                                ? a.y() + (clearance - a.y()) * ease(raw / .2f)
                                : raw <= .8f
                                        ? clearance
                                        : clearance + (b.y() - clearance) * ease((raw - .8f) / .2f);
            }
            float yaw = a.yaw() + shortest(b.yaw() - a.yaw()) * t;
            int face = raw < .45f && a.face() != b.face() ? a.face() : b.face();
            // Keep the consumed face during a closed-kan flip; the back itself covers it.
            if (b.key().startsWith("meld-") && b.face() == 37 && a.face() < 37) face = a.face();
            frame.add(
                    b.pose(
                                    a.x() + (b.x() - a.x()) * travel,
                                    y,
                                    a.z() + (b.z() - a.z()) * travel,
                                    yaw,
                                    a.pitch() + shortest(b.pitch() - a.pitch()) * t,
                                    face)
                            .scaled(a.scale() + (b.scale() - a.scale()) * t)
                            .rolled(a.roll() + shortest(b.roll() - a.roll()) * t)
                            .projected(
                                    TileViewTransform.blend(
                                            a.viewTransform(), b.viewTransform(), t)));
        }
        if (time < Math.min(.16f, duration / 2))
            frame.addAll(removed.stream().map(viewTransform).toList());
        return List.copyOf(frame);
    }

    private List<Tile> opening(float time, UnaryOperator<Tile> viewTransform) {
        List<Tile> frame = new ArrayList<>();
        float rise = ease((time - .45f) / .95f);
        if (time < 2.15f) {
            for (int i = 0; i < 136; i++) frame.add(TableLayout.wall(i, -.37f * (1 - rise)));
            return List.copyOf(frame);
        }
        int[] handIndex = new int[4];
        Set<Integer> moving = new HashSet<>();
        for (Tile original : target) {
            Tile tile = viewTransform.apply(original);
            if (tile.key().startsWith("hand")) {
                int index = handIndex[tile.seat()]++;
                boolean drawn = tile.key().endsWith("-drawn");
                var deal =
                        drawn
                                ? null
                                : wall.deal.stream()
                                        .filter(d -> d.seat() == tile.seat() && d.index() == index)
                                        .findFirst()
                                        .orElseThrow();
                int slot = drawn ? wall.firstDraw : deal.slot();
                int packet = drawn ? 16 : deal.packet();
                float raw = drawn ? (time - 3.912f) / .120f : (time - 2.15f - packet * .075f) / .3f;
                if (raw <= 0) continue;
                moving.add(slot);
                float t = drawn ? Math.clamp(raw, 0, 1) : ease(raw);
                Tile origin = TableLayout.wall(slot, 0);
                frame.add(
                        tile.pose(
                                        origin.x() + (tile.x() - origin.x()) * t,
                                        origin.y()
                                                + (tile.y() - origin.y()) * t
                                                + (drawn
                                                        ? 0
                                                        : (float) Math.sin(t * Math.PI) * .16f),
                                        origin.z() + (tile.z() - origin.z()) * t,
                                        tile.yaw(),
                                        90 + shortest(tile.pitch() - 90) * t,
                                        t < .5f ? -1 : tile.face())
                                .rolled(tile.roll() * t)
                                .projected(TileViewTransform.blend(null, tile.viewTransform(), t)));
            } else if (!tile.key().startsWith("wall-")) frame.add(tile);
        }
        Set<Integer> remaining = new HashSet<>(wall.remaining);
        for (int i = 0; i < 136; i++) {
            if (moving.contains(i)) continue;
            if (remaining.contains(i)) {
                final int slot = i;
                frame.add(
                        target.stream()
                                .filter(t -> t.key().equals("wall-" + slot))
                                .findFirst()
                                .orElseThrow());
            } else frame.add(TableLayout.wall(i, 0));
        }
        return List.copyOf(frame);
    }

    List<TableDice.Die> dice(long now) {
        return opening ? TableDice.sample(wall.roller, wall.dice, elapsed(now)) : List.of();
    }

    private static float shortest(float angle) {
        return (angle % 360 + 540) % 360 - 180;
    }
}
