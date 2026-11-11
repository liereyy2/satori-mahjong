package cn.satori.mahjong.protocol;

import java.util.ArrayList;
import java.util.List;

/** Card-local tile geometry; complete illustrated groups move together to the next row. */
public final class RiichiExampleLayout {
    private RiichiExampleLayout() {}

    public static final int TILE_WIDTH = 18, TILE_HEIGHT = 26;

    public record Tile(
            int id, int x, int y, int width, int height, boolean turned, boolean winning) {}

    public record Label(String key, int x, int y, int width) {}

    public record Layout(List<Tile> tiles, List<Label> labels, int height) {}

    public static Layout layout(RiichiHelpExamples.Example example, int width) {
        var tiles = new ArrayList<Tile>();
        var labels = new ArrayList<Label>();
        int x = 2, y = 2, rowHeight = 0;
        for (int groupIndex = 0; groupIndex < example.groups().size(); groupIndex++) {
            var group = example.groups().get(groupIndex);
            int groupWidth =
                    group.tiles().size() * (TILE_WIDTH + 2)
                            - 2
                            + (group.kind().open() ? TILE_HEIGHT - TILE_WIDTH : 0);
            if (groupWidth + 4 > width)
                throw new IllegalArgumentException("Example viewport narrower than one group");
            if (x + groupWidth + 2 > width) {
                x = 2;
                y += rowHeight + 8;
                rowHeight = 0;
            }
            int groupX = x;
            for (int tileIndex = 0; tileIndex < group.tiles().size(); tileIndex++) {
                boolean turned = group.kind().open() && tileIndex == 0;
                boolean back =
                        group.kind() == RiichiHelpExamples.Kind.ANKAN
                                && (tileIndex == 0 || tileIndex == group.tiles().size() - 1);
                int tileWidth = turned ? TILE_HEIGHT : TILE_WIDTH;
                int tileHeight = turned ? TILE_WIDTH : TILE_HEIGHT;
                boolean winning =
                        example.winMethod() != RiichiHelpExamples.WinMethod.INITIAL
                                && example.winGroup() == groupIndex
                                && example.winIndex() == tileIndex;
                tiles.add(
                        new Tile(
                                back ? 37 : group.tiles().get(tileIndex),
                                x,
                                y + TILE_HEIGHT - tileHeight,
                                tileWidth,
                                tileHeight,
                                turned,
                                winning));
                x += tileWidth + 2;
            }
            boolean labelled = group.kind() != RiichiHelpExamples.Kind.HAND;
            if (labelled)
                labels.add(
                        new Label(
                                group.kind().labelKey(), groupX, y + TILE_HEIGHT + 3, groupWidth));
            rowHeight = Math.max(rowHeight, TILE_HEIGHT + (labelled ? 14 : 0));
            x += 6;
        }
        return new Layout(List.copyOf(tiles), List.copyOf(labels), y + rowHeight + 2);
    }
}
