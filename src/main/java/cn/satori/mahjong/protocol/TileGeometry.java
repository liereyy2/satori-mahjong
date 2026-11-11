package cn.satori.mahjong.protocol;

import org.joml.Matrix4f;

/** Physical tile bounds in block units, shared by layout and the native model. */
public final class TileGeometry {
    public static final float WIDTH = 1.82f / 16;
    public static final float HEIGHT = WIDTH * 1.34f;
    public static final float THICKNESS = WIDTH * .83f;
    public static final float GAP = .0015f;
    public static final float PITCH = WIDTH + GAP;
    public static final int RIVER_COLUMNS = 6;
    public static final int RIVER_ROWS = 4;
    public static final float RIVER_SCALE = .82f;
    public static final float WALL_SCALE = .92f;
    public static final float WALL_AWAY = 1.025f;
    public static final float HAND_PITCH = WIDTH * ReferenceHandProjection.step();
    // MahjongBoard3D aligns six normal discards with each edge of the center panel.
    public static final float CENTER_HALF =
            (RIVER_COLUMNS * WIDTH + (RIVER_COLUMNS - 1) * GAP) * RIVER_SCALE / 2;

    private TileGeometry() {}

    /** Hidden faces (-1) still have a physical body. Only -2 denotes a riichi stick. */
    public static boolean isTileBody(TableScene.Tile tile) {
        return tile.face() != -2;
    }

    /** Tile local coordinates to world coordinates relative to the center block. */
    public static Matrix4f transform(TableScene.Tile tile, float tableDegrees) {
        return new Matrix4f()
                .translate(.5f, 0, .5f)
                .rotateY((float) Math.toRadians(tableDegrees))
                .mul(localTransform(tile));
    }

    public static Matrix4f localTransform(TableScene.Tile tile) {
        return (tile.viewTransform() == null ? new Matrix4f() : tile.viewTransform().matrix())
                .translate(tile.x(), tile.y(), tile.z())
                .rotateY((float) Math.toRadians(tile.yaw()))
                .rotateX((float) Math.toRadians(tile.pitch()))
                .rotateZ((float) Math.toRadians(tile.roll()))
                .scale(tile.scale());
    }

    public static float flatWidth(boolean sideways) {
        return sideways ? HEIGHT : WIDTH;
    }

    public static float scale(String key) {
        return key.startsWith("river-") ? RIVER_SCALE : key.startsWith("wall-") ? WALL_SCALE : 1;
    }
}
