package cn.satori.mahjong.client;

import cn.satori.mahjong.*;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;

/** A real block-built filming set, isolated from production worlds. */
final class VideoRoomBuilder {
    private static final BlockState WOOD = Blocks.DARK_OAK_PLANKS.defaultBlockState();

    static void build(ServerLevel level, BlockPos center, String game) {
        for (int x = -13; x <= 13; x++)
            for (int z = -14; z <= 14; z++) {
                put(
                        level,
                        center,
                        x,
                        -1,
                        z,
                        Math.abs(x) > 9 || Math.abs(z) > 11
                                ? Blocks.MOSS_BLOCK
                                : Blocks.SPRUCE_PLANKS);
                for (int y = 0; y <= 8; y++) put(level, center, x, y, z, Blocks.AIR);
            }
        for (int x = -9; x <= 9; x++)
            for (int z = -11; z <= 11; z++) {
                if (Math.abs(x) == 9 || Math.abs(z) == 11)
                    put(level, center, x, -1, z, Blocks.POLISHED_DEEPSLATE);
                if ((Math.abs(x) == 7 && Math.abs(z) < 9) || (Math.abs(z) == 9 && Math.abs(x) < 7))
                    put(level, center, x, -1, z, Blocks.DARK_OAK_PLANKS);
            }
        // Warm plaster, dark structural timber, and genuinely translucent windows.
        for (int x = -9; x <= 9; x++)
            for (int z = -11; z <= 11; z++) {
                if (Math.abs(x) != 9 && Math.abs(z) != 11) continue;
                for (int y = 0; y <= 5; y++) {
                    Block material =
                            y == 0 || y == 5 ? Blocks.DARK_OAK_PLANKS : Blocks.SMOOTH_SANDSTONE;
                    boolean window =
                            y >= 1
                                    && y <= 3
                                    && (z == -11 && Math.abs(x) < 8 || x == 9 && Math.abs(z) < 9);
                    if (window) material = Blocks.LIGHT_GRAY_STAINED_GLASS;
                    if (z == 11 && Math.abs(x) <= 2 && y < 4) material = Blocks.AIR;
                    put(level, center, x, y, z, material);
                }
            }
        for (int x : new int[] {-9, -6, 0, 6, 9})
            for (int z : new int[] {-11, 11})
                for (int y = 0; y <= 5; y++)
                    if (!(z == 11 && x == 0))
                        put(level, center, x, y, z, Blocks.STRIPPED_DARK_OAK_LOG);
        for (int z : new int[] {-8, -4, 0, 4, 8})
            for (int y = 1; y <= 3; y++) put(level, center, 9, y, z, Blocks.STRIPPED_DARK_OAK_LOG);
        for (int x = -9; x <= 9; x++)
            for (int z = -11; z <= 11; z++)
                put(
                        level,
                        center,
                        x,
                        6,
                        z,
                        x % 4 == 0 || z % 5 == 0 ? Blocks.DARK_OAK_PLANKS : Blocks.SPRUCE_SLAB);
        for (int x : new int[] {-5, 5})
            for (int z : new int[] {-6, 5}) {
                put(level, center, x, 5, z, Blocks.DARK_OAK_FENCE);
                level.setBlockAndUpdate(
                        center.offset(x, 4, z),
                        Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
            }
        // Paper light panels along the back wall.
        for (int x : new int[] {-7, -3, 3, 7}) {
            put(level, center, x, 1, -10, Blocks.SEA_LANTERN);
            put(level, center, x, 2, -10, Blocks.WHITE_STAINED_GLASS);
            put(level, center, x, 3, -10, Blocks.SPRUCE_TRAPDOOR);
        }
        for (int z = -8; z <= 7; z++) {
            put(level, center, -8, 0, z, Blocks.DARK_OAK_SLAB);
            if (Math.floorMod(z, 3) == 0) {
                put(level, center, -8, 1, z, Blocks.BOOKSHELF);
                put(level, center, -8, 2, z, Blocks.POTTED_BAMBOO);
            }
        }
        for (int x = -6; x <= 6; x++) {
            if (Math.abs(x) <= 2) continue;
            level.setBlockAndUpdate(
                    center.offset(x, 0, 9),
                    Blocks.DARK_OAK_STAIRS
                            .defaultBlockState()
                            .setValue(StairBlock.FACING, Direction.SOUTH));
        }
        // Low tea table and plants, all normal Minecraft blocks.
        for (int x = 5; x <= 7; x++) {
            put(level, center, x, 0, 7, Blocks.DARK_OAK_SLAB);
            if (x != 6) put(level, center, x, 1, 7, Blocks.FLOWER_POT);
        }
        for (int x : new int[] {-7, 7}) {
            put(level, center, x, 0, -8, Blocks.DECORATED_POT);
            put(level, center, x, 1, -8, Blocks.OAK_FENCE);
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++)
                    level.setBlockAndUpdate(
                            center.offset(x + dx, 2, -8 + dz),
                            Blocks.AZALEA_LEAVES
                                    .defaultBlockState()
                                    .setValue(LeavesBlock.PERSISTENT, true));
        }
        for (int x = -1; x <= 1; x++)
            for (int z = -1; z <= 1; z++)
                level.setBlockAndUpdate(center.offset(x, 0, z), TableBlock.part(x, z));
        if (!TableSeating.furnish(level, center))
            throw new IllegalStateException("Filming chairs blocked");
        var table = (TableBlockEntity) level.getBlockEntity(center);
        table.link(game);
        TableLighting.refresh(level, center);
        level.setDayTime(12700);
        level.setWeatherParameters(100000, 0, false, false);
    }

    private static void put(ServerLevel level, BlockPos c, int x, int y, int z, Block block) {
        level.setBlockAndUpdate(c.offset(x, y, z), block.defaultBlockState());
    }
}
