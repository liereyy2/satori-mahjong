package cn.satori.mahjong.protocol;

/** Cloud seats advance counterclockwise; physical chairs keep their world-facing identifiers. */
public final class TableOrientation {
    private TableOrientation() {}

    public static int cloudSeat(int chair) {
        return Math.floorMod(-chair, 4);
    }

    public static int chairForSeat(int seat) {
        return Math.floorMod(-seat, 4);
    }

    public static float sceneDegrees(int viewerSeat, int chair) {
        return -(viewerSeat + chair) * 90f;
    }

    /** Anchor in front of the declaring player's hand, transformed with the physical table. */
    public static org.joml.Vector3f callAnchor(int actor, float rotation) {
        if (actor < -1 || actor > 3) throw new IllegalArgumentException("invalid_call_actor");
        return new org.joml.Vector3f(0, 1.34f, actor < 0 ? 0 : .93f)
                .rotateY((float) Math.toRadians((actor < 0 ? 0 : actor * 90) + rotation))
                .add(.5f, 0, .5f);
    }
}
