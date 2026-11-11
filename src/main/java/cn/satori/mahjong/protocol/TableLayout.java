package cn.satori.mahjong.protocol;

import cn.satori.mahjong.protocol.TableScene.Tile;

import com.google.gson.*;

import java.util.*;

/** Geometry derived only from the current authorized projection. */
public final class TableLayout {
    static final float HAND_LEFT = -1.12f;
    static final float HAND_AWAY =
            1.342f - Math.max(.015f + TileGeometry.HEIGHT / 2, .055f + TileGeometry.THICKNESS / 2);
    private static final float MELD_RIGHT = 1.22f;
    private static final float RIVER_LEFT = -TileGeometry.CENTER_HALF;
    private static final float RIVER_AWAY =
            TileGeometry.CENTER_HALF + .045f + TileGeometry.HEIGHT * TileGeometry.RIVER_SCALE / 2;

    private TableLayout() {}

    public static List<Tile> layout(CloudView view) {
        List<Tile> tiles = new ArrayList<>();
        JsonObject p = view.publicData();
        for (int seat = 0; seat < 4; seat++) {
            List<Integer> hand = new ArrayList<>(view.hand(seat));
            var winning = view.winningTile(seat);
            if (winning.isPresent()) {
                int hiddenOrFace =
                        view.tableObserver() || seat == view.viewerSeat || view.revealed(seat)
                                ? winning.getAsInt()
                                : 37;
                if (!hand.remove((Integer) hiddenOrFace))
                    throw new IllegalArgumentException("winning_tile_missing_from_hand");
            }
            boolean drawn = view.hasDrawn(seat);
            int drawnFace = drawn ? view.drawnFace(seat) : -1;
            if (drawn && !hand.remove((Integer) drawnFace))
                throw new IllegalArgumentException("drawn_tile_missing_from_hand");
            int voidSuit = view.voidSuit(seat);
            hand.sort(Comparator.comparingInt(CloudView::handOrder));
            float scale = 1;
            Map<Integer, Integer> occurrences = new HashMap<>();
            List<HandFace> handDisplay = new ArrayList<>();
            for (int face : hand)
                handDisplay.add(
                        new HandFace(
                                "hand-"
                                        + seat
                                        + "-"
                                        + face
                                        + "-"
                                        + occurrences.merge(face, 1, Integer::sum),
                                face,
                                false));
            // Sort presentation only; keys continue identifying the original physical copies.
            if (voidSuit >= 0)
                handDisplay.sort(
                        Comparator.comparingInt(
                                tile -> tile.face < 27 && tile.face / 9 == voidSuit ? 1 : 0));
            if (drawn) handDisplay.add(new HandFace("hand-" + seat + "-drawn", drawnFace, true));
            for (int i = 0; i < handDisplay.size(); i++) {
                HandFace tile = handDisplay.get(i);
                tiles.add(
                        handTile(
                                tile.key,
                                seat,
                                tile.face,
                                i,
                                scale,
                                tile.drawn,
                                view.tableObserver() || view.revealed(seat)));
            }
            if (winning.isPresent())
                tiles.add(
                        handTile(
                                "win-" + seat,
                                seat,
                                winning.getAsInt(),
                                hand.size(),
                                scale,
                                true,
                                true));
            if (p.has("discards")) {
                JsonArray river = p.getAsJsonArray("discards").get(seat).getAsJsonArray();
                int visible = 0, row = 0;
                float edge = RIVER_LEFT;
                for (int i = 0; i < river.size(); i++) {
                    JsonObject d = river.get(i).getAsJsonObject();
                    if (d.has("was_called") && d.get("was_called").getAsBoolean()) continue;
                    int nextRow =
                            Math.min(
                                    visible / TileGeometry.RIVER_COLUMNS,
                                    TileGeometry.RIVER_ROWS - 1);
                    if (nextRow != row) {
                        row = nextRow;
                        edge = RIVER_LEFT;
                    }
                    boolean sideways =
                            d.has("is_riichi_marker") && d.get("is_riichi_marker").getAsBoolean();
                    float width = TileGeometry.flatWidth(sideways) * TileGeometry.RIVER_SCALE;
                    tiles.add(
                            tile(
                                    "river-" + seat + "-" + i,
                                    seat,
                                    d.get("tile").getAsInt(),
                                    edge + width / 2,
                                    RIVER_AWAY
                                            + row
                                                    * (TileGeometry.HEIGHT + TileGeometry.GAP)
                                                    * TileGeometry.RIVER_SCALE,
                                    false,
                                    sideways));
                    edge += width + TileGeometry.GAP * TileGeometry.RIVER_SCALE;
                    visible++;
                }
            }
            if (p.has("melds")) {
                JsonArray melds = p.getAsJsonArray("melds").get(seat).getAsJsonArray();
                float edge = MELD_RIGHT;
                for (int m = 0; m < melds.size(); m++) {
                    JsonObject meld = melds.get(m).getAsJsonObject();
                    JsonArray faces = meld.getAsJsonArray("tiles");
                    boolean addedKan = meld.get("type").getAsString().equals("kakan");
                    boolean closedKan = meld.get("type").getAsString().equals("ankan");
                    int baseCount = addedKan ? 3 : faces.size();
                    boolean called = meld.has("target") && !meld.get("target").isJsonNull();
                    int direction =
                            called ? Math.floorMod(meld.get("target").getAsInt() - seat, 4) : 0;
                    // Groups are packed from right to left. Upstream (seat - 1) is on the left.
                    int calledPosition = direction == 3 ? baseCount - 1 : direction == 2 ? 1 : 0;
                    int consumed = 0;
                    for (int display = 0; display < baseCount; display++) {
                        boolean sideways = called && display == calledPosition;
                        float width = TileGeometry.flatWidth(sideways);
                        // Closed kan faces arrive left to right; packing walks right to left.
                        int original =
                                closedKan
                                        ? baseCount - 1 - display
                                        : sideways ? baseCount - 1 : consumed++;
                        Tile base =
                                tile(
                                        "meld-" + seat + "-" + m + "-" + original,
                                        seat,
                                        faces.get(original).getAsInt(),
                                        edge - width / 2,
                                        HAND_AWAY,
                                        false,
                                        sideways);
                        if (closedKan && (original == 0 || original == 3))
                            base = base.pose(base.x(), base.y(), base.z(), base.yaw(), 90, 37);
                        tiles.add(base);
                        if (addedKan && sideways) {
                            tiles.add(
                                    new Tile(
                                            "meld-" + seat + "-" + m + "-3",
                                            seat,
                                            faces.get(3).getAsInt(),
                                            base.x(),
                                            base.y() + TileGeometry.THICKNESS,
                                            base.z(),
                                            base.yaw(),
                                            false,
                                            true,
                                            base.pitch()));
                        }
                        edge -= width;
                    }
                    edge -= TileGeometry.GAP * 5;
                }
            }
        }
        if (!Set.of("waiting_players", "ready", "cancelled").contains(view.status())) {
            for (int i : view.wall.remaining) {
                Tile wall = wall(i, 0);
                for (var indicator : view.wall.indicators)
                    if (i == indicator.slot())
                        wall =
                                wall.pose(
                                        wall.x(),
                                        wall.y(),
                                        wall.z(),
                                        wall.yaw(),
                                        -90,
                                        indicator.face());
                tiles.add(wall);
            }
        }
        if (p.has("riichi_accepted"))
            for (int seat = 0; seat < 4; seat++) {
                if (p.getAsJsonArray("riichi_accepted").get(seat).getAsBoolean()) {
                    Tile stick =
                            tile(
                                    "stick-" + seat,
                                    seat,
                                    -2,
                                    0,
                                    TableCenter.STICK_RADIUS,
                                    false,
                                    false);
                    tiles.add(
                            stick.pose(
                                    stick.x(),
                                    1.050f,
                                    stick.z(),
                                    stick.yaw(),
                                    stick.pitch(),
                                    stick.face()));
                }
            }
        return List.copyOf(tiles);
    }

    private record HandFace(String key, int face, boolean drawn) {}

    private static Tile handTile(
            String key, int seat, int face, int index, float scale, boolean drawn, boolean faceUp) {
        float pitch = faceUp ? -90 : 0;
        float along =
                HAND_LEFT
                        + TileGeometry.WIDTH * scale / 2
                        + index * TileGeometry.HAND_PITCH * scale
                        + (drawn ? TileGeometry.WIDTH * scale * .4f : 0);
        Tile tile = tile(key, seat, face, along, HAND_AWAY, true, false);
        float rad = (float) Math.toRadians(pitch);
        float halfHeight =
                scale
                        * (TileGeometry.HEIGHT * (float) Math.cos(rad)
                                + TileGeometry.THICKNESS * Math.abs((float) Math.sin(rad)))
                        / 2;
        return tile.pose(tile.x(), 1.003f + halfHeight, tile.z(), tile.yaw(), pitch, face)
                .scaled(scale);
    }

    public static Tile wall(int i, float lift) {
        int seat = i / 34, pair = (i % 34) / 2;
        Tile tile =
                tile(
                        "wall-" + i,
                        seat,
                        -1,
                        (pair - 8) * TileGeometry.PITCH * TileGeometry.WALL_SCALE,
                        TileGeometry.WALL_AWAY,
                        false,
                        false);
        return tile.pose(
                tile.x(),
                tile.y()
                        + (i % 2 == 0
                                ? (TileGeometry.THICKNESS + TileGeometry.GAP)
                                        * TileGeometry.WALL_SCALE
                                : 0)
                        + lift,
                tile.z(),
                tile.yaw(),
                90,
                -1);
    }

    static Tile tile(
            String key,
            int seat,
            int face,
            float along,
            float away,
            boolean standing,
            boolean sideways) {
        float angle = -seat * (float) Math.PI / 2,
                cos = (float) Math.cos(angle),
                sin = (float) Math.sin(angle);
        return new Tile(
                key,
                seat,
                face,
                along * cos - away * sin,
                standing ? 1.081f : 1.002f + TileGeometry.THICKNESS * TileGeometry.scale(key) / 2,
                along * sin + away * cos,
                seat * 90f,
                standing,
                sideways);
    }
}
