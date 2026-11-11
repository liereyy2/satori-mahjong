package cn.satori.mahjong.protocol;

/** Integer-pixel containment of the unmodified 384 by 528 tile artwork. */
public record TileFaceBounds(int x, int y, int width, int height) {
    public static TileFaceBounds fit(int width, int height) {
        float scale = Math.min(width / 384f, height / 528f);
        int w = Math.round(384 * scale), h = Math.round(528 * scale);
        return new TileFaceBounds((width - w) / 2, (height - h) / 2, w, h);
    }
}
