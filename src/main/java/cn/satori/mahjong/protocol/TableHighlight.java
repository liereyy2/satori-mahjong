package cn.satori.mahjong.protocol;

/** A five-second local visual cue; timing never affects the hosted match. */
public final class TableHighlight {
    public static final long DURATION = 5_000_000_000L;

    private TableHighlight() {}

    public static boolean visible(long elapsedNanos) {
        return elapsedNanos >= 0 && elapsedNanos < DURATION && elapsedNanos / 250_000_000L % 2 == 0;
    }
}
