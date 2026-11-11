package cn.satori.mahjong;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.*;

import org.jspecify.annotations.Nullable;

public final class TableBlock extends BaseEntityBlock {
    public static final BooleanProperty LIT = BooleanProperty.create("lit");
    public static final IntegerProperty PART = IntegerProperty.create("part", 0, 8);
    private static final VoxelShape SHAPE =
            Shapes.or(
                    Block.box(0, 11, 0, 16, 16, 16),
                    Block.box(2, 0, 2, 5, 10, 5),
                    Block.box(11, 0, 11, 14, 10, 14));

    public TableBlock(Properties properties) {
        super(properties.lightLevel(state -> state.getValue(LIT) ? 15 : 0));
        registerDefaultState(stateDefinition.any().setValue(LIT, false).setValue(PART, 4));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(LIT, PART);
    }

    @Override
    public <T extends BlockEntity> @Nullable BlockEntityTicker<T> getTicker(
            Level level, BlockState state, BlockEntityType<T> type) {
        return state.getValue(PART) == 4 && !level.isClientSide()
                ? createTickerHelper(type, SatoriMahjong.tableEntityType(), TableLighting::tick)
                : null;
    }

    @Override
    protected void affectNeighborsAfterRemoval(
            BlockState state, ServerLevel level, BlockPos pos, boolean moving) {
        super.affectNeighborsAfterRemoval(state, level, pos, moving);
        TableAssembly.removed(level, center(pos, state), state.getValue(PART) == 4);
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return simpleCodec(TableBlock::new);
    }

    @Override
    public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return state.getValue(PART) == 4 ? new TableBlockEntity(pos, state) : null;
    }

    @Override
    protected RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected VoxelShape getShape(
            BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    public void setPlacedBy(
            Level level,
            BlockPos pos,
            BlockState state,
            @Nullable LivingEntity placer,
            ItemStack stack) {
        if (placer != null && level.getBlockEntity(pos) instanceof TableBlockEntity table)
            table.setBuilder(placer.getUUID());
        if (!level.isClientSide()) TableAssembly.assemble(level, pos);
    }

    @Override
    public @Nullable BlockState getStateForPlacement(BlockPlaceContext context) {
        return TableAssembly.canPlace(context) ? defaultBlockState() : null;
    }

    public static BlockState part(int x, int z) {
        return SatoriMahjong.table().defaultBlockState().setValue(PART, (x + 1) * 3 + z + 1);
    }

    public static BlockPos center(BlockPos pos, BlockState state) {
        int part = state.getValue(PART);
        return pos.offset(1 - part / 3, 0, 1 - part % 3);
    }

    public static boolean complete(Level level, BlockPos center) {
        for (int x = -1; x <= 1; x++)
            for (int z = -1; z <= 1; z++) {
                BlockPos pos = center.offset(x, 0, z);
                if (!level.hasChunkAt(pos)) return false;
                var state = level.getBlockState(pos);
                if (!state.is(SatoriMahjong.table()) || state.getValue(PART) != (x + 1) * 3 + z + 1)
                    return false;
            }
        return true;
    }

    @Override
    public BlockState playerWillDestroy(
            Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide() && player.isCreative() && state.getValue(PART) != 4)
            level.destroyBlock(center(pos, state), false);
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    protected InteractionResult useWithoutItem(
            BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        BlockPos center = center(pos, state);
        if (!complete(level, center)) {
            player.displayClientMessage(Component.translatable("satori.table.incomplete"), true);
            return InteractionResult.SUCCESS;
        }
        if (player instanceof ServerPlayer serverPlayer)
            TableSeating.sit(
                    serverPlayer, center, TableSeating.direction(center, player.position()));
        return InteractionResult.SUCCESS;
    }
}
