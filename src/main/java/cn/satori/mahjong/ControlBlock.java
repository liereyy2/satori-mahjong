package cn.satori.mahjong;

import com.mojang.serialization.MapCodec;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/** All Control blocks open the same account configuration for this MC instance. */
public final class ControlBlock extends Block {
    public static final MapCodec<ControlBlock> CODEC = simpleCodec(ControlBlock::new);

    public ControlBlock(Properties properties) {
        super(properties);
    }

    @Override
    public MapCodec<ControlBlock> codec() {
        return CODEC;
    }

    @Override
    protected InteractionResult useWithoutItem(
            BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (player instanceof ServerPlayer serverPlayer)
            HostedServer.get(serverPlayer.level().getServer()).openControl(serverPlayer, pos);
        return InteractionResult.SUCCESS;
    }
}
