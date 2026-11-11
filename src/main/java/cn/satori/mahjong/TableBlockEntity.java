package cn.satori.mahjong;

import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.*;

import java.util.UUID;

/** World metadata only. No Satori identity, credentials or mahjong state can enter NBT. */
public final class TableBlockEntity extends BlockEntity {
    public final TableLobby lobby = new TableLobby(this);
    private String gameId = "";
    private String builder = "";
    private UUID cloudTable = UUID.randomUUID();

    public TableBlockEntity(BlockPos pos, BlockState state) {
        super(SatoriMahjong.tableEntityType(), pos, state);
    }

    public String gameId() {
        return gameId;
    }

    public UUID cloudTableId() {
        return cloudTable;
    }

    @Override
    public void preRemoveSideEffects(BlockPos pos, BlockState state) {
        if (level instanceof net.minecraft.server.level.ServerLevel serverLevel)
            HostedServer.get(serverLevel.getServer()).destroyTable(this);
        super.preRemoveSideEffects(pos, state);
    }

    public void setBuilder(UUID id) {
        builder = id.toString();
        setChanged();
    }

    public boolean canEdit(UUID id) {
        return builder.equals(id.toString());
    }

    public void link(String id) {
        gameId = id;
        setChanged();
        if (level != null)
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        output.putString("game_id", gameId);
        output.putString("builder", builder);
        output.putString("cloud_table_id", cloudTable.toString());
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        gameId = input.getStringOr("game_id", "");
        builder = input.getStringOr("builder", "");
        cloudTable = UUID.fromString(input.getStringOr("cloud_table_id", cloudTable.toString()));
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        tag.putString("game_id", gameId);
        tag.putString("cloud_table_id", cloudTable.toString());
        return tag;
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
