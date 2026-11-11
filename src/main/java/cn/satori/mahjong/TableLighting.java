package cn.satori.mahjong;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/** Continuous table illumination; Minecraft propagates its light into the surrounding world. */
public final class TableLighting {
    private TableLighting() {}

    public static void tick(
            Level level,
            BlockPos center,
            net.minecraft.world.level.block.state.BlockState state,
            TableBlockEntity table) {
        if (level.getGameTime() % 20 == 0) {
            refresh(level, center);
            if (level instanceof net.minecraft.server.level.ServerLevel serverLevel)
                HostedServer.get(serverLevel.getServer()).observe(table);
        }
    }

    public static void refresh(Level level, BlockPos center) {
        var state = level.getBlockState(center);
        if (!state.is(SatoriMahjong.table()) || state.getValue(TableBlock.PART) != 4) return;
        set(level, center, TableBlock.complete(level, center));
    }

    public static void clear(Level level, BlockPos center) {
        set(level, center, false);
    }

    private static void set(Level level, BlockPos center, boolean lit) {
        for (int x = -1; x <= 1; x++)
            for (int z = -1; z <= 1; z++) {
                BlockPos pos = center.offset(x, 0, z);
                if (!level.hasChunkAt(pos)) continue;
                var state = level.getBlockState(pos);
                if (state.is(SatoriMahjong.table()) && state.getValue(TableBlock.LIT) != lit)
                    level.setBlock(pos, state.setValue(TableBlock.LIT, lit), 3);
            }
    }
}
