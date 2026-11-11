package cn.satori.mahjong;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** Physical tables are assigned exclusively by their authenticated cloud coordinator. */
public final class TableLinkHandler {
    private TableLinkHandler() {}

    public static void handle(ServerPlayer player, TablePackets.Link packet) {
        player.displayClientMessage(
                Component.translatable("satori.message.managed_table_required"), true);
    }
}
