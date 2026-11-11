package cn.satori.mahjong.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;

import org.junit.jupiter.api.Test;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

class ExchangeSelectionTest {
    static JsonObject fixture(String name) throws IOException {
        try (var reader =
                new InputStreamReader(
                        Objects.requireNonNull(
                                ExchangeSelectionTest.class.getResourceAsStream(
                                        "/" + name + ".json")),
                        StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    @Test
    void threePhysicalSelectionsResolveToTheExactServerActionAndClearForNextDecision()
            throws Exception {
        var raw = fixture("sichuan-exchange");
        var view = new CloudView(raw, UUID.fromString(raw.get("game_id").getAsString()), 0);
        for (var action : view.actions()) {
            var selection = new ExchangeSelection();
            selection.synchronize(view);
            var available =
                    new ArrayList<>(
                            TableLayout.layout(view).stream()
                                    .filter(t -> t.seat() == 0 && t.key().startsWith("hand-"))
                                    .toList());
            Collections.reverse(available);
            var selectedSlots = new LinkedHashSet<String>();
            for (int face : CloudPresentation.actionTiles(action)) {
                var tile =
                        available.stream().filter(t -> t.face() == face).findFirst().orElseThrow();
                assertTrue(selection.canToggle(view, tile.key(), face));
                selection.toggle(view, tile.key(), face);
                selectedSlots.add(view.handSlots().get(tile.key()));
                available.remove(tile);
            }
            assertEquals(3, selection.size());
            assertEquals(action.actionId(), selection.action(view).orElseThrow().actionId());
            assertEquals(
                    selectedSlots,
                    new LinkedHashSet<>(
                            selection
                                    .action(view)
                                    .orElseThrow()
                                    .display()
                                    .getAsJsonArray("consume_slot_ids")
                                    .asList()
                                    .stream()
                                    .map(JsonElement::getAsString)
                                    .toList()));
            var fourth = available.getFirst();
            assertFalse(selection.canToggle(view, fourth.key(), fourth.face()));
            selection.toggle(view, fourth.key(), fourth.face());
            assertEquals(3, selection.size());
            var changed = raw.deepCopy();
            changed.getAsJsonObject("decision").addProperty("decision_id", "next-decision");
            selection.synchronize(new CloudView(changed, view.gameId, 0));
            assertEquals(0, selection.size());
            assertTrue(selection.action(view).isEmpty());
        }
    }

    @Test
    void exhaustiveSettlementContainsAllHandsMeldsAndBalancedTransferLedger() throws Exception {
        var fixture = fixture("sichuan-draw");
        var entries =
                fixture.getAsJsonObject("presentation")
                        .getAsJsonObject("settlement")
                        .getAsJsonArray("entries");
        var draw =
                entries.asList().stream()
                        .map(JsonElement::getAsJsonObject)
                        .filter(e -> e.get("kind").getAsString().equals("ryukyoku"))
                        .findFirst()
                        .orElseThrow();
        assertEquals(4, draw.getAsJsonArray("hands").size());
        for (var hand : draw.getAsJsonArray("hands"))
            assertNotNull(hand.getAsJsonObject().getAsJsonArray("melds"));
        int[] net = new int[4];
        for (var raw : draw.getAsJsonObject("sichuan").getAsJsonArray("transfers")) {
            var transfer = raw.getAsJsonObject();
            int amount = transfer.get("amount").getAsInt();
            assertTrue(amount > 0);
            net[transfer.get("payer").getAsInt()] -= amount;
            net[transfer.get("payee").getAsInt()] += amount;
        }
        for (int seat = 0; seat < 4; seat++)
            assertEquals(draw.getAsJsonArray("deltas").get(seat).getAsInt(), net[seat]);
    }
}
