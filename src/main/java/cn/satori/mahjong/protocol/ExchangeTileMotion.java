package cn.satori.mahjong.protocol;

import cn.satori.mahjong.protocol.TableScene.Tile;

import com.google.gson.JsonObject;

import java.util.*;
import java.util.function.UnaryOperator;

/** Four authorized batches travel as physical tiles, including exchanges of identical faces. */
final class ExchangeTileMotion {
    static final float DURATION = 2.12f;
    private static final float SEND = .44f, SWAP = 1.04f, RECEIVE = 1.46f, INSERT = 1.54f;

    private record Transfer(Tile from, Tile to, int source, int index) {}

    private record Survivor(Tile from, Tile compact, Tile to) {}

    private final List<Tile> fixed;
    private final List<Transfer> transfers = new ArrayList<>();
    private final List<Survivor> survivors = new ArrayList<>();
    private final int direction;

    ExchangeTileMotion(List<Tile> before, List<Tile> after, JsonObject event) {
        direction = event.get("direction").getAsInt();
        var batches = event.getAsJsonArray("exchanges");
        var sources = event.getAsJsonArray("source_indices");
        if (direction < 1 || direction > 3 || batches.size() != 4 || sources.size() != 4)
            throw new IllegalArgumentException("invalid_exchange_animation");
        var remainingBefore =
                new ArrayList<>(before.stream().filter(ExchangeTileMotion::hand).toList());
        var remainingAfter =
                new ArrayList<>(after.stream().filter(ExchangeTileMotion::hand).toList());
        // Reserve all twelve outgoing and incoming tiles before matching survivors. A received
        // copy can have exactly the same face and sorted key as a tile being given away.
        for (int source = 0; source < 4; source++) {
            var batch = batches.get(source).getAsJsonArray();
            if (batch.size() != 3) throw new IllegalArgumentException("invalid_exchange_batch");
            for (int index = 0; index < 3; index++) {
                int face = CloudPresentation.tileId(batch.get(index).getAsString());
                int actor = source;
                var sourceHand = before.stream().filter(t -> hand(t) && t.seat() == actor).toList();
                int position = sources.get(source).getAsJsonArray().get(index).getAsInt();
                if (position < 0 || position >= sourceHand.size())
                    throw new IllegalArgumentException("invalid_exchange_source");
                var from = sourceHand.get(position);
                if ((from.face() != face && from.face() != 37) || !remainingBefore.remove(from))
                    throw new IllegalArgumentException("invalid_exchange_source");
                var to = take(remainingAfter, (source + direction) % 4, face);
                transfers.add(new Transfer(from, to, source, index));
            }
        }
        for (int seat = 0; seat < 4; seat++) {
            int actor = seat;
            var oldHand = remainingBefore.stream().filter(t -> t.seat() == actor).toList();
            for (int index = 0; index < oldHand.size(); index++) {
                var from = oldHand.get(index);
                var to = take(remainingAfter, seat, from.face());
                // Fill the ten surviving slots while the exchanged tiles are on the table.
                var slot =
                        before.stream()
                                .filter(t -> hand(t) && t.seat() == actor)
                                .toList()
                                .get(index);
                survivors.add(
                        new Survivor(
                                from,
                                from.pose(
                                        slot.x(),
                                        from.y(),
                                        slot.z(),
                                        from.yaw(),
                                        from.pitch(),
                                        from.face()),
                                to));
            }
        }
        if (!remainingAfter.isEmpty())
            throw new IllegalArgumentException("unallocated_exchange_hand");
        fixed = after.stream().filter(t -> !hand(t)).toList();
    }

    private static boolean hand(Tile tile) {
        return tile.key().startsWith("hand-");
    }

    private static Tile take(List<Tile> tiles, int seat, int face) {
        // Unknown is an authorized anonymous hand, not an inferred hidden tile identity.
        var tile =
                tiles.stream()
                        .filter(t -> t.seat() == seat && (t.face() == face || t.face() == 37))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "missing_exchange_tile:" + seat + ":" + face));
        tiles.remove(tile);
        return tile;
    }

    List<Tile> sample(float time, UnaryOperator<Tile> transform) {
        var frame = new ArrayList<Tile>();
        fixed.forEach(t -> frame.add(transform.apply(t)));
        for (var survivor : survivors) {
            var from = transform.apply(survivor.from());
            var compact = transform.apply(survivor.compact());
            var to = transform.apply(survivor.to());
            frame.add(
                    time < INSERT
                            ? blend(from, compact, ease((time - .18f) / .30f))
                            : blend(compact, to, ease((time - INSERT) / .28f)));
        }
        float turn = direction == 3 ? -1 : direction;
        for (var transfer : transfers) {
            var a = transform.apply(transfer.from());
            var b = transform.apply(transfer.to());
            var sent = stage(a, transfer.source(), transfer.index());
            var arrived = stage(b, transfer.to().seat(), transfer.index());
            var received = besideHand(b, transfer.index());
            Tile tile;
            if (time < SEND) tile = lifted(a, sent, time / SEND);
            else if (time < SWAP)
                tile =
                        stage(
                                a,
                                transfer.source() + turn * ease((time - SEND) / (SWAP - SEND)),
                                transfer.index());
            else if (time < RECEIVE)
                tile = lifted(arrived, received, (time - SWAP) / (RECEIVE - SWAP));
            else if (time < INSERT) tile = received;
            else tile = lifted(received, b, (time - INSERT) / (DURATION - INSERT));
            // In-flight pieces are not interactive hand slots. Never expose a received face
            // while it is still in an opponent's concealed row.
            frame.add(
                    new Tile(
                            "exchange-" + transfer.source() + "-" + transfer.index(),
                            tile.seat(),
                            tile.face(),
                            tile.x(),
                            tile.y(),
                            tile.z(),
                            tile.yaw(),
                            tile.standing(),
                            false,
                            tile.pitch(),
                            tile.scale(),
                            tile.roll(),
                            tile.viewTransform()));
        }
        return List.copyOf(frame);
    }

    private static Tile stage(Tile tile, float seat, int index) {
        double angle = seat * Math.PI / 2;
        float along = (index - 1) * TileGeometry.PITCH;
        return tile.pose(
                        (float) (along * Math.cos(angle) + .64f * Math.sin(angle)),
                        1.003f + TileGeometry.THICKNESS / 2,
                        (float) (-along * Math.sin(angle) + .64f * Math.cos(angle)),
                        seat * 90,
                        90,
                        37)
                .scaled(1)
                .rolled(0)
                .projected(null);
    }

    private static Tile besideHand(Tile tile, int index) {
        var position =
                TableLayout.tile(
                        "exchange",
                        tile.seat(),
                        tile.face(),
                        .96f + (index - 1) * TileGeometry.PITCH,
                        TableLayout.HAND_AWAY,
                        true,
                        false);
        return tile.pose(
                position.x(), tile.y(), position.z(), tile.yaw(), tile.pitch(), tile.face());
    }

    private static Tile lifted(Tile from, Tile to, float progress) {
        float t = Math.clamp(progress, 0, 1);
        var tile = blend(from, to, ease((t - .2f) / .6f));
        float clearance = Math.max(1.36f, Math.max(from.y(), to.y()) + .21f);
        float y =
                t < .2f
                        ? from.y() + (clearance - from.y()) * ease(t / .2f)
                        : t <= .8f
                                ? clearance
                                : clearance + (to.y() - clearance) * ease((t - .8f) / .2f);
        return tile.pose(tile.x(), y, tile.z(), tile.yaw(), tile.pitch(), tile.face());
    }

    private static Tile blend(Tile a, Tile b, float t) {
        float yaw = (b.yaw() - a.yaw() + 540) % 360 - 180;
        return new Tile(
                b.key(),
                t == 0 ? a.seat() : b.seat(),
                t < .5f ? a.face() : b.face(),
                a.x() + (b.x() - a.x()) * t,
                a.y() + (b.y() - a.y()) * t,
                a.z() + (b.z() - a.z()) * t,
                a.yaw() + yaw * t,
                b.standing(),
                false,
                a.pitch() + (b.pitch() - a.pitch()) * t,
                a.scale() + (b.scale() - a.scale()) * t,
                a.roll() + (b.roll() - a.roll()) * t,
                TileViewTransform.blend(a.viewTransform(), b.viewTransform(), t));
    }

    private static float ease(float t) {
        return TableTransition.ease(t);
    }
}
