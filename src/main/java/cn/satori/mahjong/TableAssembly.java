package cn.satori.mahjong;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;

/** One inventory item owns a 3x3 physical table and four chairs. No independent frame items. */
public final class TableAssembly {
    private TableAssembly() {}

    public static List<BlockPos> footprint(BlockPos center) {
        var positions = new ArrayList<BlockPos>();
        for (int x = -1; x <= 1; x++)
            for (int z = -1; z <= 1; z++) positions.add(center.offset(x, 0, z));
        for (int seat = 0; seat < 4; seat++) positions.add(TableSeating.chair(center, seat));
        return List.copyOf(positions);
    }

    public static boolean canPlace(BlockPlaceContext context) {
        Level level = context.getLevel();
        for (BlockPos pos : footprint(context.getClickedPos())) {
            int dx = pos.getX() - context.getClickedPos().getX();
            int dz = pos.getZ() - context.getClickedPos().getZ();
            var occupied =
                    Math.abs(dx) <= 1 && Math.abs(dz) <= 1
                            ? TableBlock.part(dx, dz)
                            : SatoriMahjong.chair()
                                    .defaultBlockState()
                                    .setValue(
                                            ChairBlock.FACING,
                                            TableSeating.outward(
                                                            TableSeating.direction(
                                                                    context.getClickedPos(),
                                                                    pos.getCenter()))
                                                    .getOpposite());
            if (!level.hasChunkAt(pos)
                    || level.isOutsideBuildHeight(pos.above())
                    || !level.getWorldBorder().isWithinBounds(pos)
                    || !level.getBlockState(pos).canBeReplaced(context)
                    || !level.getBlockState(pos.above()).isAir()
                    || !level.isUnobstructed(
                            occupied, pos, net.minecraft.world.phys.shapes.CollisionContext.empty())
                    || context.getPlayer() != null
                            && (!level.mayInteract(context.getPlayer(), pos)
                                    || !context.getPlayer()
                                            .mayUseItemAt(
                                                    pos,
                                                    context.getClickedFace(),
                                                    context.getItemInHand()))) return false;
        }
        return true;
    }

    public static void assemble(Level level, BlockPos center) {
        for (int x = -1; x <= 1; x++)
            for (int z = -1; z <= 1; z++) {
                if (x == 0 && z == 0) continue;
                level.setBlockAndUpdate(center.offset(x, 0, z), TableBlock.part(x, z));
            }
        TableSeating.furnish(level, center);
        TableLighting.refresh(level, center);
    }

    public static void removed(ServerLevel level, BlockPos center, boolean wasCenter) {
        if (!wasCenter) {
            var state = level.getBlockState(center);
            if (state.is(SatoriMahjong.table()) && state.getValue(TableBlock.PART) == 4)
                level.destroyBlock(center, true);
            return;
        }
        for (BlockPos pos : footprint(center)) {
            if (pos.equals(center)) continue;
            var state = level.getBlockState(pos);
            boolean owned =
                    state.is(SatoriMahjong.table()) && TableBlock.center(pos, state).equals(center)
                            || state.is(SatoriMahjong.chair())
                                    && pos.relative(state.getValue(ChairBlock.FACING), 2)
                                            .equals(center);
            if (owned) level.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
        }
    }
}
