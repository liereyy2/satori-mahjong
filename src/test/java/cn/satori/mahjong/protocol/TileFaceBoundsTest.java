package cn.satori.mahjong.protocol;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TileFaceBoundsTest {
    @Test
    void compressedButtonsAndSettlementSlotsContainUnstretchedArtwork() {
        for (int width = 7; width <= 30; width++) {
            for (int height : new int[] {12, 18, 20, 23, 40}) {
                var fit = TileFaceBounds.fit(width, height);
                assertTrue(fit.x() >= 0 && fit.y() >= 0);
                assertTrue(fit.x() + fit.width() <= width);
                assertTrue(fit.y() + fit.height() <= height);
                assertTrue(Math.abs(fit.width() - fit.height() * 384f / 528) <= .7f);
                assertTrue(Math.abs(width - fit.width() - 2 * fit.x()) <= 1);
                assertTrue(Math.abs(height - fit.height() - 2 * fit.y()) <= 1);
            }
        }
    }
}
