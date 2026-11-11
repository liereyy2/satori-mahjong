package cn.satori.mahjong.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;

final class SettlementLayoutTest {
    @Test
    void completeHandsAndAllQuadCombinationsStayOnOneBaselineWithinThePanel() {
        for (int melds = 0; melds <= 4; melds++) {
            for (int mask = 0; mask < 1 << melds; mask++) {
                var sizes = new java.util.ArrayList<Integer>();
                for (int i = 0; i < melds; i++) sizes.add((mask & 1 << i) == 0 ? 3 : 4);
                for (boolean win : new boolean[] {false, true}) {
                    int count = (win ? 14 : 13) - 3 * melds;
                    var row = SettlementLayout.handRow(count, sizes, win);
                    assertTrue(row.scale() > 0 && row.scale() <= 1);
                    assertTrue(
                            row.width() * row.scale() <= SettlementLayout.HAND_ROW_WIDTH + .001f);
                    int end = row.handX(count - 1) + SettlementLayout.HAND_TILE_WIDTH;
                    for (int i = 0; i < sizes.size(); i++) {
                        assertTrue(row.meldStarts().get(i) >= end + SettlementLayout.GROUP_GAP);
                        end =
                                row.meldStarts().get(i)
                                        + sizes.get(i) * SettlementLayout.MELD_TILE_WIDTH;
                    }
                    assertEquals(row.width(), end);
                    assertEquals(25, SettlementLayout.HAND_TILE_HEIGHT);
                    assertEquals(25, 5 + SettlementLayout.MELD_TILE_HEIGHT);
                }
            }
        }
    }

    @Test
    void winningTileGapAndMeldOrderArePreserved() {
        var row = SettlementLayout.handRow(8, java.util.List.of(3, 4), true);
        assertEquals(25, row.handX(7) - row.handX(6));
        assertEquals(8 * 18 + 7 + 7, row.meldStarts().getFirst());
        assertEquals(row.meldStarts().getFirst() + 3 * 14 + 7, row.meldStarts().getLast());
    }

    private JsonObject result() {
        return JsonParser.parseString(
                        """
                        {"scores_before":[-1000,30000,45000,26000],
                         "scores_after":[-9000,38000,45000,26000],
                         "deltas":[-8000,8000,0,0],"confirmed":[false,true,false,true]}
                        """)
                .getAsJsonObject();
    }

    @Test
    void eachViewerHasSelfBelowAndCounterclockwiseOpponents() {
        for (int viewer = 0; viewer < 4; viewer++) {
            var cards = SettlementLayout.scores(result(), viewer);
            var own = cards.getFirst();
            assertEquals(viewer, own.seat());
            var right = cards.get(1);
            var opposite = cards.get(2);
            var left = cards.get(3);
            assertEquals((viewer + 1) % 4, right.seat());
            assertEquals((viewer + 2) % 4, opposite.seat());
            assertEquals((viewer + 3) % 4, left.seat());
            assertEquals(own.x(), opposite.x());
            assertEquals(left.y(), right.y());
            assertTrue(left.x() < own.x() && own.x() < right.x());
            assertTrue(opposite.y() < left.y() && left.y() < own.y());
        }
    }

    @Test
    void showsAuthoritativeBeforeAndSignedChangeInsteadOfCurrentScore() {
        var result = result();
        String source = result.toString();
        var cards = SettlementLayout.scores(result, 3);
        assertEquals(26000, cards.get(0).before());
        assertTrue(cards.get(0).confirmed());
        assertEquals(-1000, cards.get(1).before());
        assertEquals("-8000", cards.get(1).changeText());
        assertEquals(30000, cards.get(2).before());
        assertEquals("+8000", cards.get(2).changeText());
        assertEquals("0", cards.get(3).changeText());
        assertEquals(source, result.toString());
    }

    @Test
    void scoreCardsFitBesideDetailsWithoutOverlap() {
        var cards = SettlementLayout.scores(result(), 0);
        for (var card : cards) {
            assertTrue(card.x() >= 330 && card.y() >= 70);
            assertTrue(card.x() + SettlementLayout.CARD_WIDTH <= SettlementLayout.WIDTH - 10);
            assertTrue(card.y() + SettlementLayout.CARD_HEIGHT < 270);
            for (var other : cards) {
                if (other.seat() == card.seat()) continue;
                assertTrue(
                        card.x() + SettlementLayout.CARD_WIDTH <= other.x()
                                || other.x() + SettlementLayout.CARD_WIDTH <= card.x()
                                || card.y() + SettlementLayout.CARD_HEIGHT <= other.y()
                                || other.y() + SettlementLayout.CARD_HEIGHT <= card.y());
            }
        }
    }
}
