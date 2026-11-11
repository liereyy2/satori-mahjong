package cn.satori.mahjong.protocol;

import com.google.gson.JsonObject;

import java.util.List;
import java.util.stream.IntStream;

/** Four table directions and unmodified Rust settlement values, relative to the viewer. */
public final class SettlementLayout {
    public static final int WIDTH = 600, HEIGHT = 320;
    public static final int CARD_WIDTH = 116, CARD_HEIGHT = 58;
    public static final int HAND_TILE_WIDTH = 18, HAND_TILE_HEIGHT = 25;
    public static final int MELD_TILE_WIDTH = 14, MELD_TILE_HEIGHT = 20;
    public static final int HAND_ROW_WIDTH = 284, GROUP_GAP = 7;
    private static final int[][] POSITIONS = {{402, 202}, {470, 137}, {402, 72}, {334, 137}};

    private SettlementLayout() {}

    /** Presentation coordinates only; the authorized hand and meld order are unchanged. */
    public record HandRow(
            int handCount, boolean separateWin, List<Integer> meldStarts, int width, float scale) {
        public int handX(int index) {
            return index * HAND_TILE_WIDTH
                    + (separateWin && index == handCount - 1 ? GROUP_GAP : 0);
        }
    }

    public static HandRow handRow(int handCount, List<Integer> meldSizes, boolean separateWin) {
        if (handCount < 0 || meldSizes.stream().anyMatch(size -> size <= 0))
            throw new IllegalArgumentException("settlement_hand_geometry");
        int width = handCount * HAND_TILE_WIDTH + (separateWin && handCount > 0 ? GROUP_GAP : 0);
        var starts = new java.util.ArrayList<Integer>();
        for (int size : meldSizes) {
            if (width > 0) width += GROUP_GAP;
            starts.add(width);
            width += size * MELD_TILE_WIDTH;
        }
        return new HandRow(
                handCount,
                separateWin,
                List.copyOf(starts),
                width,
                width == 0 ? 1f : Math.min(1f, HAND_ROW_WIDTH / (float) width));
    }

    public record ScoreCard(int seat, int x, int y, int before, int delta, boolean confirmed) {
        public String changeText() {
            return (delta > 0 ? "+" : "") + delta;
        }
    }

    public static List<ScoreCard> scores(JsonObject result, int viewerSeat) {
        if (viewerSeat < 0 || viewerSeat > 3) throw new IllegalArgumentException("viewer_seat");
        return IntStream.range(0, 4)
                .mapToObj(
                        slot -> {
                            int seat = (viewerSeat + slot) % 4;
                            return new ScoreCard(
                                    seat,
                                    POSITIONS[slot][0],
                                    POSITIONS[slot][1],
                                    result.getAsJsonArray("scores_before").get(seat).getAsInt(),
                                    result.getAsJsonArray("deltas").get(seat).getAsInt(),
                                    result.getAsJsonArray("confirmed").get(seat).getAsBoolean());
                        })
                .toList();
    }
}
