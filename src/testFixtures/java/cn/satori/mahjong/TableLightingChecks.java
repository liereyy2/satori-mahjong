package cn.satori.mahjong;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

/** Isolated structure lifecycle checks, shared by the two native dedicated servers. */
public final class TableLightingChecks {
    public static void verify(ServerLevel level, BlockPos existing) {
        BlockPos center = existing.offset(12, 8, 0);
        TableAssemblyChecks.verify(level, center.offset(0, 4, 8));
        long time = level.getDayTime();
        level.getChunkAt(center);
        try {
            level.setBlockAndUpdate(center, SatoriMahjong.table().defaultBlockState());
            TableAssembly.assemble(level, center);
            level.setDayTime(6000);
            level.updateSkyBrightness();
            TableLighting.refresh(level, center);
            check(level, center, true);
            level.setDayTime(18000);
            level.updateSkyBrightness();
            TableLighting.refresh(level, center);
            check(level, center, true);
            for (int i = 0; i < 10; i++) TableLighting.refresh(level, center);
            check(level, center, true);
            level.setBlockAndUpdate(center.offset(1, 0, 1), Blocks.AIR.defaultBlockState());
            TableLighting.refresh(level, center);
            check(level, center, false);
            level.setBlockAndUpdate(center, SatoriMahjong.table().defaultBlockState());
            TableAssembly.assemble(level, center);
            TableLighting.refresh(level, center);
            check(level, center, true);
            level.setBlockAndUpdate(center, Blocks.AIR.defaultBlockState());
            check(level, center, false);
        } finally {
            for (int x = -1; x <= 1; x++)
                for (int z = -1; z <= 1; z++)
                    level.setBlockAndUpdate(center.offset(x, 0, z), Blocks.AIR.defaultBlockState());
            level.setDayTime(time);
            level.updateSkyBrightness();
        }
    }

    private static void check(ServerLevel level, BlockPos center, boolean lit) {
        for (int x = -1; x <= 1; x++)
            for (int z = -1; z <= 1; z++) {
                var state = level.getBlockState(center.offset(x, 0, z));
                if (state.getBlock() instanceof TableBlock
                        && (state.getValue(TableBlock.LIT) != lit
                                || state.getLightEmission() != (lit ? 15 : 0)))
                    throw new AssertionError(
                            "Table lighting lifecycle: " + state + " expected " + lit);
            }
    }
}
