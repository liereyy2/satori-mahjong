package cn.satori.mahjong;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.*;

/** A table-owned chair. Facing points toward its center, two blocks away. */
public final class ChairBlock extends HorizontalDirectionalBlock {
    public ChairBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return simpleCodec(ChairBlock::new);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
        b.add(FACING);
    }

    @Override
    protected VoxelShape getShape(
            BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        VoxelShape seat = Shapes.or(Block.box(1, 5, 1, 15, 10, 15), Block.box(6, 0, 6, 10, 5, 10));
        return Shapes.or(
                seat,
                switch (state.getValue(FACING)) {
                    case NORTH -> Block.box(1, 9, 12, 15, 23, 16);
                    case SOUTH -> Block.box(1, 9, 0, 15, 23, 4);
                    case EAST -> Block.box(0, 9, 1, 4, 23, 15);
                    case WEST -> Block.box(12, 9, 1, 16, 23, 15);
                    default -> throw new IllegalStateException("Horizontal chair facing required");
                });
    }

    @Override
    protected void affectNeighborsAfterRemoval(
            BlockState state,
            net.minecraft.server.level.ServerLevel level,
            BlockPos pos,
            boolean moving) {
        super.affectNeighborsAfterRemoval(state, level, pos, moving);
        TableAssembly.removed(level, pos.relative(state.getValue(FACING), 2), false);
    }

    @Override
    public BlockState playerWillDestroy(
            Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide() && player.isCreative())
            level.destroyBlock(pos.relative(state.getValue(FACING), 2), false);
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    protected InteractionResult useWithoutItem(
            BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player instanceof ServerPlayer serverPlayer) {
            BlockPos center = pos.relative(state.getValue(FACING), 2);
            TableSeating.sit(serverPlayer, center, TableSeating.direction(center, pos.getCenter()));
        }
        return InteractionResult.SUCCESS;
    }
}
