package cn.satori.mahjong.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;

import org.junit.jupiter.api.Test;

import java.io.*;
import java.nio.charset.StandardCharsets;

class HostedTableViewsTest {
    @Test
    void publicMetadataIsIndependentAndNeverCopiesPrivateStream() throws Exception {
        var table = table(examples().getAsJsonObject("opening"));
        table.add("seats", JsonParser.parseString("[{\"name\":\"Alice\"}]"));
        table.add("game_id", JsonNull.INSTANCE);
        var state = HostedTableViews.publicState(table);
        assertFalse(state.has("render_stream"));
        assertTrue(state.get("game_id").isJsonNull());
        assertEquals(table.get("seats"), state.get("seats"));
        state.getAsJsonArray("seats").get(0).getAsJsonObject().addProperty("name", "Bob");
        assertEquals(
                "Alice",
                table.getAsJsonArray("seats").get(0).getAsJsonObject().get("name").getAsString());
    }

    private JsonObject examples() throws IOException {
        try (var in = getClass().getResourceAsStream("/table-stream-examples.json")) {
            return JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                    .getAsJsonObject();
        }
    }

    private JsonObject table(JsonObject stream) {
        var table = new JsonObject();
        table.add("render_stream", stream);
        return table;
    }

    @Test
    void seatedRecipientsReceiveOnlyOwnHandAndOutsideObserversReceiveAllFour() throws Exception {
        var stream = examples().getAsJsonObject("opening");
        var table = table(stream);
        for (int seat = 0; seat < 6; seat++) {
            var packet = HostedTableViews.packet(table, seat, false);
            var data = packet.getAsJsonObject("view");
            var view =
                    new CloudView(
                            data,
                            java.util.UUID.fromString(data.get("game_id").getAsString()),
                            Math.min(seat, 4));
            assertTrue(view.actions().isEmpty());
            assertEquals(seat == 4, view.tableObserver());
            for (int other = 0; other < 4; other++) {
                boolean visible = seat == 4 || other == view.viewerSeat;
                assertTrue(
                        view.hand(other).stream()
                                .allMatch(tile -> visible ? tile < 37 : tile == 37));
            }
            assertTrue(view.discardFor("hand-0-0-1").isEmpty());
            assertFalse(packet.has("render_stream"));
            for (var raw : packet.getAsJsonArray("updates"))
                assertEquals(
                        seat == 4,
                        raw.getAsJsonObject().getAsJsonObject("view").has("table_hands"));
            if (seat == 4) {
                var tiles =
                        TableLayout.layout(view).stream()
                                .filter(t -> t.key().startsWith("hand-"))
                                .toList();
                assertTrue(tiles.size() >= 52);
                assertTrue(tiles.stream().allMatch(t -> t.face() < 37 && t.pitch() == -90));
            }
        }
        assertFalse(HostedTableViews.publicState(table).has("render_stream"));
        assertTrue(table.has("render_stream"));
        assertThrows(
                CloudView.ServerUpdateRequired.class,
                () -> HostedTableViews.packet(new JsonObject(), 4, true));
    }

    @Test
    void matchParticipantOffChairStaysPrivateAndOnlyOutsiderGetsOmniscientRole() throws Exception {
        var stream = examples().getAsJsonObject("opening");
        var table = table(stream);
        var members = stream.getAsJsonObject("participants");
        var participant = java.util.UUID.fromString(members.keySet().iterator().next());
        int seat = members.get(participant.toString()).getAsInt();
        assertEquals(seat, HostedTableViews.access(table, participant, -1).role());
        var outsider = new java.util.UUID(0, 999);
        assertEquals(4, HostedTableViews.access(table, outsider, -1).role());
        assertEquals(5, HostedTableViews.access(table, outsider, 1).role());
        assertEquals(seat, HostedTableViews.access(table, participant, (seat + 1) % 4).role());
        var restricted = HostedTableViews.access(table, participant, -1);
        assertTrue(restricted.revision() > 0);
        members.addProperty(participant.toString(), 5);
        stream.addProperty("participant_revision", restricted.revision() + 1);
        var replaced = HostedTableViews.access(table, participant, -1);
        assertEquals(5, replaced.role());
        var masked = HostedTableViews.packet(table, replaced.role(), true).getAsJsonObject("view");
        assertTrue(masked.get("private").isJsonNull());
        assertFalse(masked.has("table_hands"));
        members.addProperty(participant.toString(), seat);
        stream.addProperty("participant_revision", replaced.revision() + 1);
        var returned = HostedTableViews.access(table, participant, -1);
        assertEquals(restricted.role(), returned.role());
        assertTrue(returned.revision() > restricted.revision());
        // The server resets on permission revision, even when a seat switch returns
        // to the same role between polls, so it never replays another holder's hand.
        assertEquals(HostedTableViews.access(table, outsider, -1).revision(), 0);
        stream.remove("participants");
        assertThrows(
                CloudView.ServerUpdateRequired.class,
                () -> HostedTableViews.access(table, participant, -1));
    }

    @Test
    void outsideFeedAnimatesRealOpeningAndDiscardFramesWithoutPeriodicReset() throws Exception {
        var examples = examples();
        var feed = new TableRenderFeed();
        feed.accept(HostedTableViews.packet(table(examples.getAsJsonObject("before")), 4, true));
        feed.accept(HostedTableViews.packet(table(examples.getAsJsonObject("opening")), 4, false));
        long now = feed.scene().now();
        assertTrue(feed.scene().animating(now));
        assertFalse(feed.scene().dice(now + 2_000_000_000L).isEmpty());
        var actions = HostedTableViews.packet(table(examples.getAsJsonObject("actions")), 4, false);
        feed.accept(actions);
        assertTrue(feed.scene().animating(feed.scene().now()));
        var latest = feed.view();
        // An unchanged poll must not clear the in-flight opening/discards.
        var unchanged = actions.deepCopy();
        unchanged.add("updates", new JsonArray());
        feed.accept(unchanged);
        assertTrue(feed.scene().animating(feed.scene().now()));
        assertEquals(latest.cursor, feed.view().cursor);
        feed.accept(actions); // Retransmission is idempotent.
        assertEquals(latest.cursor, feed.view().cursor);
        var finalTiles = feed.scene().sample(now + 120_000_000_000L);
        assertEquals(TableLayout.layout(latest), finalTiles);
    }

    @Test
    void longCatchupIsSplitIntoOrderedNativePayloadsWithoutDroppingFrames() throws Exception {
        var examples = examples();
        for (int seat = 0; seat < 6; seat++) {
            var feed = new TableRenderFeed();
            feed.accept(
                    HostedTableViews.packet(
                            table(examples.getAsJsonObject("opening")), seat, true));
            var packets =
                    HostedTableViews.packets(
                            table(examples.getAsJsonObject("actions")), seat, false);
            assertEquals(
                    examples.getAsJsonObject("actions").getAsJsonArray("updates").size(),
                    packets.size());
            for (var packet : packets) {
                assertTrue(packet.toString().length() < 65_000);
                assertTrue(packet.toString().getBytes(StandardCharsets.UTF_8).length < 190_000);
                feed.accept(packet);
            }
            assertEquals(
                    examples.getAsJsonObject("actions").get("cursor").getAsString(),
                    feed.view().cursor);
        }
    }

    @Test
    void roleChangeAtSameVersionImmediatelyDropsOmniscientAnimationAndHands() throws Exception {
        var stream = table(examples().getAsJsonObject("opening"));
        var feed = new TableRenderFeed();
        feed.accept(HostedTableViews.packet(stream, 4, true));
        assertTrue(feed.view().tableObserver());
        var outside = feed.scene();
        feed.accept(HostedTableViews.packet(stream, 0, true));
        assertNotSame(outside, feed.scene());
        assertFalse(feed.view().tableObserver());
        assertFalse(feed.scene().animating(feed.scene().now()));
        for (int seat = 1; seat < 4; seat++)
            assertTrue(feed.view().hand(seat).stream().allMatch(t -> t == 37));
        feed.accept(HostedTableViews.packet(stream, 4, true));
        assertTrue(feed.view().tableObserver());
        assertTrue(feed.view().hand(1).stream().allMatch(t -> t < 37));
        var invalid =
                stream.getAsJsonObject("render_stream")
                        .getAsJsonArray("views")
                        .get(4)
                        .getAsJsonObject()
                        .deepCopy();
        invalid.addProperty("viewer_seat", 0);
        assertThrows(
                IllegalArgumentException.class,
                () -> new CloudView(invalid, feed.view().gameId, 0));
    }

    @Test
    void missingSequenceIsRejectedAtomicallyAndExplicitSnapshotResynchronizes() throws Exception {
        var examples = examples();
        var feed = new TableRenderFeed();
        feed.accept(HostedTableViews.packet(table(examples.getAsJsonObject("opening")), 4, true));
        var before = feed.view();
        var broken = HostedTableViews.packet(table(examples.getAsJsonObject("actions")), 4, false);
        broken.getAsJsonArray("updates").remove(1);
        assertThrows(IllegalArgumentException.class, () -> feed.accept(broken));
        assertSame(before, feed.view());
        assertFalse(feed.scene().animating(feed.scene().now()));
        broken.addProperty("reset", true);
        feed.accept(broken);
        assertEquals(
                examples.getAsJsonObject("actions").get("cursor").getAsString(),
                feed.view().cursor);
        assertFalse(feed.scene().animating(feed.scene().now()));
    }

    @Test
    void presentationClockRemainsAuthoritativeAcrossAnimationAndSnapshotReset() throws Exception {
        var clock = new java.util.concurrent.atomic.AtomicLong(1_000_000_000L);
        var examples = examples();
        var feed = new TableRenderFeed(clock::get);
        feed.accept(HostedTableViews.packet(table(examples.getAsJsonObject("before")), 4, true));
        feed.accept(HostedTableViews.packet(table(examples.getAsJsonObject("opening")), 4, false));
        clock.addAndGet(2_000_000_000L);
        assertEquals(clock.get(), feed.scene().now());
        assertFalse(feed.scene().dice(feed.scene().now()).isEmpty());
        feed.accept(HostedTableViews.packet(table(examples.getAsJsonObject("opening")), 4, true));
        clock.addAndGet(1_000_000_000L);
        assertEquals(clock.get(), feed.scene().now());
        assertFalse(feed.scene().animating(feed.scene().now()));
        feed.accept(HostedTableViews.packet(table(examples.getAsJsonObject("actions")), 4, false));
        assertTrue(feed.scene().animating(clock.get()));
    }
}
