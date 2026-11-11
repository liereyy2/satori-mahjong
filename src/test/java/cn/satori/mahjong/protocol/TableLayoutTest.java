package cn.satori.mahjong.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class TableLayoutTest {
    private static CloudView view(JsonObject raw) {
        return new CloudView(
                raw,
                UUID.fromString(raw.get("game_id").getAsString()),
                raw.get("viewer_seat").getAsInt());
    }

    private static void hand(JsonObject raw, int... ids) {
        JsonArray hand = new JsonArray();
        for (int i = 0; i < ids.length; i++) {
            JsonObject tile = new JsonObject();
            tile.addProperty("tile_id", ids[i]);
            tile.addProperty("slot_id", "h-" + i);
            hand.add(tile);
        }
        raw.getAsJsonObject("private").add("hand", hand);
    }

    private static List<TableScene.Tile> group(CloudView view, String prefix, int seat) {
        return TableScene.layout(view).stream()
                .filter(t -> t.seat() == seat && t.key().startsWith(prefix))
                .toList();
    }

    private static float along(TableScene.Tile t) {
        return switch (t.seat()) {
            case 0 -> t.x();
            case 1 -> -t.z();
            case 2 -> -t.x();
            default -> t.z();
        };
    }

    private static float away(TableScene.Tile t) {
        return switch (t.seat()) {
            case 0 -> t.z();
            case 1 -> t.x();
            case 2 -> -t.z();
            default -> -t.x();
        };
    }

    @Test
    void voidGroupEndsTheCompactHandAndDrawStaysSeparateForEverySeat() throws Exception {
        for (int seat = 0; seat < 4; seat++)
            for (int draw : new int[] {0, 9}) {
                var raw =
                        ExchangeSelectionTest.fixture("sichuan-examples")
                                .getAsJsonObject("playing");
                raw.addProperty("viewer_seat", seat);
                raw.getAsJsonObject("public")
                        .getAsJsonObject("sichuan")
                        .add("void_suits", JsonParser.parseString("[0,0,0,0]"));
                hand(raw, 0, 1, 9, 18, draw);
                raw.getAsJsonObject("private").addProperty("last_tsumo_tile", draw);
                raw.getAsJsonObject("display")
                        .getAsJsonArray("drawn_seats")
                        .set(seat, new JsonPrimitive(true));
                var view = view(raw);
                var display = group(view, "hand-", seat);
                assertEquals(
                        List.of(9, 18, 0, 1, draw),
                        display.stream().map(TableScene.Tile::face).toList());
                assertTrue(display.getLast().key().endsWith("-drawn"));
                assertEquals(
                        (TileGeometry.HAND_PITCH + .4f * TileGeometry.WIDTH)
                                * display.getLast().scale(),
                        along(display.getLast()) - along(display.get(3)),
                        .00001);
                for (int i = 1; i < display.size(); i++)
                    assertTrue(along(display.get(i)) > along(display.get(i - 1)));
                for (var tile : display) assertTrue(view.handSlots().containsKey(tile.key()));
            }
    }

    @Test
    void voidSuitMovesRightWithoutChangingPhysicalDiscardSlotIdentity() throws Exception {
        var raw = ExchangeSelectionTest.fixture("sichuan-examples").getAsJsonObject("playing");
        raw.getAsJsonObject("public")
                .getAsJsonObject("sichuan")
                .add("void_suits", JsonParser.parseString("[0,1,2,0]"));
        var view = view(raw);
        var standing =
                group(view, "hand-", view.viewerSeat).stream()
                        .filter(t -> !t.key().endsWith("drawn"))
                        .toList();
        boolean reachedVoid = false;
        var slots = view.handSlots();
        for (var tile : standing) {
            boolean isVoid = tile.face() / 9 == view.voidSuit(view.viewerSeat);
            if (isVoid) reachedVoid = true;
            else
                assertFalse(
                        reachedVoid, "A non-void standing tile was placed after the void group");
            assertTrue(slots.containsKey(tile.key()), "Sorting changed the physical tile key");
            var action = view.discardFor(tile.key());
            if (action.isPresent())
                assertEquals(
                        List.of(slots.get(tile.key())),
                        action.get().display().getAsJsonArray("consume_slot_ids").asList().stream()
                                .map(JsonElement::getAsString)
                                .toList());
        }
        assertTrue(reachedVoid);
    }

    @Test
    void closedKansHaveBlueOuterBacksAndTheRedFiveSecondFromEverySeat() {
        for (int viewer = 0; viewer < 4; viewer++) {
            for (int face : new int[] {0, 27, 34, 35, 36}) {
                int normal = face >= 34 ? 4 + (face - 34) * 9 : face;
                JsonObject raw = ProtocolTest.projection(viewer);
                for (int seat = 0; seat < 4; seat++) {
                    var meld =
                            JsonParser.parseString(
                                    "{\"type\":\"ankan\",\"target\":null,\"tiles\":[37,"
                                            + face
                                            + ","
                                            + normal
                                            + ",37]}");
                    raw.getAsJsonObject("public")
                            .getAsJsonArray("melds")
                            .get(seat)
                            .getAsJsonArray()
                            .add(meld);
                }
                String original = raw.toString();
                for (int seat = 0; seat < 4; seat++) {
                    var kan =
                            group(view(raw), "meld-", seat).stream()
                                    .sorted(Comparator.comparingDouble(TableLayoutTest::along))
                                    .toList();
                    assertEquals(
                            List.of(37, face, normal, 37),
                            kan.stream().map(TableScene.Tile::face).toList());
                    assertEquals(
                            List.of(90f, -90f, -90f, 90f),
                            kan.stream().map(TableScene.Tile::pitch).toList());
                    assertTrue(kan.stream().noneMatch(t -> t.sideways() || t.standing()));
                    for (int i = 1; i < 4; i++)
                        assertEquals(
                                TileGeometry.WIDTH,
                                along(kan.get(i)) - along(kan.get(i - 1)),
                                .000001f);
                }
                assertEquals(original, raw.toString());
            }
        }
    }

    @Test
    void allAuthorizedHandsSortRedFivesWithTheirSuitWithoutMutatingCloudData() {
        for (int seat = 0; seat < 4; seat++) {
            JsonObject raw = ProtocolTest.projection(seat);
            hand(raw, 36, 4, 0, 34, 4, 27, 35);
            raw.getAsJsonObject("public")
                    .getAsJsonArray("revealed_hands")
                    .set((seat + 1) % 4, JsonParser.parseString("[33,2,0,2]"));
            String original = raw.toString();
            CloudView view = view(raw);
            assertEquals(List.of(0, 4, 4, 34, 35, 36, 27), view.hand(seat));
            assertEquals(
                    view.hand(seat),
                    group(view, "hand", seat).stream().map(TableScene.Tile::face).toList());
            assertEquals(List.of(0, 2, 2, 33), view.hand((seat + 1) % 4));
            assertEquals(Collections.nCopies(13, 37), view.hand((seat + 2) % 4));
            assertEquals(original, raw.toString());
        }
    }

    @Test
    void fourHandsTouchAndKeepLeftAndOuterFeltMargins() {
        JsonObject raw = ProtocolTest.projection(0);
        hand(raw, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6);
        for (int seat = 0; seat < 4; seat++) {
            var tiles = group(view(raw), "hand", seat);
            float scale = tiles.getFirst().scale();
            assertTrue(along(tiles.getFirst()) - TileGeometry.WIDTH * scale / 2 + 1.342f >= .22f);
            for (int i = 0; i < tiles.size(); i++) {
                double pitch = Math.toRadians(tiles.get(i).pitch());
                double halfDepth =
                        scale
                                * (TileGeometry.THICKNESS * Math.cos(pitch)
                                        + TileGeometry.HEIGHT * Math.abs(Math.sin(pitch)))
                                / 2;
                assertTrue(1.342f - away(tiles.get(i)) - halfDepth > (seat == 0 ? .01f : .05f));
                if (i > 0)
                    assertEquals(
                            0,
                            along(tiles.get(i))
                                    - along(tiles.get(i - 1))
                                    - TileGeometry.WIDTH * scale,
                            .000001f);
            }
        }
    }

    @Test
    void riversPackVisibleDiscardsInOrderAndAccountForRiichiWidth() {
        JsonObject raw = ProtocolTest.projection(0);
        for (int seat = 0; seat < 4; seat++) {
            JsonArray river =
                    raw.getAsJsonObject("public")
                            .getAsJsonArray("discards")
                            .get(seat)
                            .getAsJsonArray();
            for (int i = 0; i < 14; i++) {
                JsonObject tile = new JsonObject();
                tile.addProperty("tile", 13 - i);
                tile.addProperty("was_called", i == 1);
                tile.addProperty("is_riichi_marker", i == 3);
                river.add(tile);
            }
        }
        for (int seat = 0; seat < 4; seat++) {
            var river = group(view(raw), "river", seat);
            assertEquals(13, river.size());
            assertEquals(
                    List.of(13, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0),
                    river.stream().map(TableScene.Tile::face).toList());
            assertTrue(river.get(2).sideways());
            for (int i = 1; i < river.size(); i++) {
                var previous = river.get(i - 1);
                var tile = river.get(i);
                if (i % 6 != 0) {
                    float gap =
                            along(tile)
                                    - TileGeometry.flatWidth(tile.sideways())
                                            * TileGeometry.RIVER_SCALE
                                            / 2
                                    - along(previous)
                                    - TileGeometry.flatWidth(previous.sideways())
                                            * TileGeometry.RIVER_SCALE
                                            / 2;
                    assertEquals(.0015f * TileGeometry.RIVER_SCALE, gap, .000001f);
                    assertEquals(away(previous), away(tile), .000001f);
                } else {
                    assertEquals(
                            .0015f * TileGeometry.RIVER_SCALE,
                            away(tile)
                                    - away(previous)
                                    - TileGeometry.HEIGHT * TileGeometry.RIVER_SCALE,
                            .000001f);
                    assertEquals(along(river.getFirst()), along(tile), .000001f);
                }
            }
        }
    }

    @Test
    void fourRiversAlignWithTheCenterSquareAndStayInsideTheWalls() {
        JsonObject raw = ProtocolTest.projection(0);
        for (int seat = 0; seat < 4; seat++) {
            JsonArray river =
                    raw.getAsJsonObject("public")
                            .getAsJsonArray("discards")
                            .get(seat)
                            .getAsJsonArray();
            for (int i = 0; i < 24; i++) {
                JsonObject tile = new JsonObject();
                tile.addProperty("tile", i);
                river.add(tile);
            }
        }
        for (int seat = 0; seat < 4; seat++) {
            var river = group(view(raw), "river", seat);
            for (int row = 0; row < 4; row++) {
                var first = river.get(row * 6);
                var last = river.get(row * 6 + 5);
                assertEquals(
                        -TileGeometry.CENTER_HALF,
                        along(first) - TileGeometry.WIDTH * TileGeometry.RIVER_SCALE / 2,
                        .000001f);
                assertEquals(
                        TileGeometry.CENTER_HALF,
                        along(last) + TileGeometry.WIDTH * TileGeometry.RIVER_SCALE / 2,
                        .000001f);
                assertEquals(0, along(first) + along(last), .000001f);
            }
            assertEquals(
                    .045f,
                    away(river.getFirst())
                            - TileGeometry.HEIGHT * TileGeometry.RIVER_SCALE / 2
                            - TileGeometry.CENTER_HALF,
                    .000001f);
            assertTrue(
                    away(river.getLast()) + TileGeometry.HEIGHT * TileGeometry.RIVER_SCALE / 2
                            < TileGeometry.WALL_AWAY
                                    - TileGeometry.HEIGHT * TileGeometry.WALL_SCALE / 2);
        }
    }

    @Test
    void longRiversContinueAlongTheFourthRowWithoutEnteringTheWallSlot() {
        JsonObject raw = ProtocolTest.projection(0);
        for (int seat = 0; seat < 4; seat++) {
            JsonArray source =
                    raw.getAsJsonObject("public")
                            .getAsJsonArray("discards")
                            .get(seat)
                            .getAsJsonArray();
            for (int i = 0; i < 28; i++) {
                JsonObject discard = new JsonObject();
                discard.addProperty("tile", i);
                discard.addProperty("was_called", i == 19 || i == 24);
                discard.addProperty("is_riichi_marker", i == 23);
                source.add(discard);
            }
        }
        for (int seat = 0; seat < 4; seat++) {
            var river = group(view(raw), "river", seat);
            assertEquals(26, river.size());
            assertEquals("river-" + seat + "-27", river.getLast().key());
            for (int i = 19; i < river.size(); i++) {
                var previous = river.get(i - 1);
                var current = river.get(i);
                assertEquals(away(previous), away(current), .000001f);
                assertEquals(
                        TileGeometry.GAP * TileGeometry.RIVER_SCALE,
                        along(current)
                                - TileGeometry.flatWidth(current.sideways())
                                        * TileGeometry.RIVER_SCALE
                                        / 2
                                - along(previous)
                                - TileGeometry.flatWidth(previous.sideways())
                                        * TileGeometry.RIVER_SCALE
                                        / 2,
                        .000001f);
                assertTrue(
                        away(current) + TileGeometry.HEIGHT * TileGeometry.RIVER_SCALE / 2
                                < TileGeometry.WALL_AWAY - .09f);
                assertTrue(
                        along(current)
                                        + TileGeometry.flatWidth(current.sideways())
                                                * TileGeometry.RIVER_SCALE
                                                / 2
                                < TileGeometry.WALL_AWAY - .09f);
            }
        }
    }

    @Test
    void calledDiscardsLeaveNoHoleAtTheStartMiddleOrRowBoundary() {
        for (int called : List.of(0, 3, 5, 6, 12)) {
            JsonObject raw = ProtocolTest.projection(0);
            JsonArray river =
                    raw.getAsJsonObject("public")
                            .getAsJsonArray("discards")
                            .get(0)
                            .getAsJsonArray();
            for (int i = 0; i < 13; i++) {
                JsonObject tile = new JsonObject();
                tile.addProperty("tile", i);
                tile.addProperty("was_called", i == called);
                river.add(tile);
            }
            var packed = group(view(raw), "river", 0);
            assertEquals(12, packed.size());
            for (int i = 0; i < packed.size(); i++) {
                assertEquals("river-0-" + (i < called ? i : i + 1), packed.get(i).key());
                assertEquals(
                        -TileGeometry.CENTER_HALF
                                + TileGeometry.WIDTH * TileGeometry.RIVER_SCALE / 2
                                + i % 6 * TileGeometry.PITCH * TileGeometry.RIVER_SCALE,
                        along(packed.get(i)),
                        .000001f);
                assertEquals(
                        away(packed.getFirst())
                                + i
                                        / 6
                                        * (TileGeometry.HEIGHT + TileGeometry.GAP)
                                        * TileGeometry.RIVER_SCALE,
                        away(packed.get(i)),
                        .000001f);
            }
        }
    }

    @Test
    void everyMeldTouchesAtPhysicalEdgesAndAddedKanTouchesItsBase() {
        for (String type : List.of("chi", "pon", "daiminkan", "ankan", "kakan"))
            for (int direction = 1; direction < 4; direction++) {
                JsonObject raw = ProtocolTest.projection(0);
                for (int seat = 0; seat < 4; seat++) {
                    var meld =
                            JsonParser.parseString(
                                            "{\"type\":\""
                                                    + type
                                                    + "\",\"tiles\":"
                                                    + (type.contains("kan")
                                                            ? "[0,0,0,0]"
                                                            : "[0,0,0]")
                                                    + "}")
                                    .getAsJsonObject();
                    if (type.equals("ankan")) meld.add("target", JsonNull.INSTANCE);
                    else meld.addProperty("target", (seat + direction) % 4);
                    raw.getAsJsonObject("public")
                            .getAsJsonArray("melds")
                            .get(seat)
                            .getAsJsonArray()
                            .add(meld);
                }
                for (int seat = 0; seat < 4; seat++) {
                    var all = group(view(raw), "meld-", seat);
                    var row =
                            all.stream()
                                    .filter(t -> !type.equals("kakan") || !t.key().endsWith("-3"))
                                    .sorted(Comparator.comparingDouble(TableLayoutTest::along))
                                    .toList();
                    for (int i = 1; i < row.size(); i++) {
                        var a = row.get(i - 1);
                        var b = row.get(i);
                        assertEquals(
                                0,
                                along(b)
                                        - along(a)
                                        - (TileGeometry.flatWidth(a.sideways())
                                                        + TileGeometry.flatWidth(b.sideways()))
                                                / 2,
                                1e-6f,
                                type + " contains a gap or overlap");
                    }
                    if (type.equals("kakan")) {
                        var added =
                                all.stream()
                                        .filter(t -> t.key().endsWith("-3"))
                                        .findFirst()
                                        .orElseThrow();
                        var base =
                                row.stream()
                                        .filter(TableScene.Tile::sideways)
                                        .findFirst()
                                        .orElseThrow();
                        assertEquals(base.x(), added.x(), 1e-6f);
                        assertEquals(base.z(), added.z(), 1e-6f);
                        assertEquals(TileGeometry.THICKNESS, added.y() - base.y(), 1e-6f);
                    }
                }
            }
    }

    @Test
    void fourOpenKansFitBesideTheRemainingHandWithoutCrossingTableEdges() {
        JsonObject raw = ProtocolTest.projection(0);
        hand(raw, 4, 4);
        for (int seat = 0; seat < 4; seat++) {
            raw.getAsJsonObject("public")
                    .getAsJsonArray("hand_counts")
                    .set(seat, new JsonPrimitive(2));
            JsonArray melds =
                    raw.getAsJsonObject("public")
                            .getAsJsonArray("melds")
                            .get(seat)
                            .getAsJsonArray();
            for (int i = 0; i < 4; i++) {
                JsonObject meld =
                        JsonParser.parseString("{\"type\":\"daiminkan\",\"tiles\":[0,0,0,0]}")
                                .getAsJsonObject();
                meld.addProperty("target", (seat + i % 3 + 1) % 4);
                melds.add(meld);
            }
        }
        for (int seat = 0; seat < 4; seat++) {
            var hand = group(view(raw), "hand", seat);
            var melds = group(view(raw), "meld", seat);
            assertEquals(16, melds.size());
            float left = Float.POSITIVE_INFINITY;
            for (var tile : melds) {
                left = Math.min(left, along(tile) - TileGeometry.flatWidth(tile.sideways()) / 2);
                assertTrue(along(tile) + TileGeometry.flatWidth(tile.sideways()) / 2 <= 1.221f);
                assertTrue(
                        away(tile)
                                        + (tile.sideways()
                                                        ? TileGeometry.WIDTH
                                                        : TileGeometry.HEIGHT)
                                                / 2
                                < 1.342f);
            }
            assertTrue(
                    left - along(hand.getLast()) - TileGeometry.WIDTH * hand.getLast().scale() / 2
                            > .025f);
        }
    }
}
