package cn.satori.mahjong;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.networking.v1.*;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.*;
import net.minecraft.resources.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.PushReaction;

public final class SatoriMahjong implements ModInitializer {
    public static final String ID = "satori_mahjong";
    public static final TableBlock TABLE = register("table");
    public static final ControlBlock CONTROL =
            Registry.register(
                    BuiltInRegistries.BLOCK,
                    id("control"),
                    new ControlBlock(
                            BlockBehaviour.Properties.of()
                                    .setId(ResourceKey.create(Registries.BLOCK, id("control")))
                                    .strength(3f)
                                    .sound(SoundType.METAL)));
    public static final ChairBlock CHAIR =
            Registry.register(
                    BuiltInRegistries.BLOCK,
                    id("chair"),
                    new ChairBlock(
                            BlockBehaviour.Properties.of()
                                    .setId(ResourceKey.create(Registries.BLOCK, id("chair")))
                                    .strength(2.5f)
                                    .noOcclusion()
                                    .sound(SoundType.WOOD)
                                    .pushReaction(PushReaction.BLOCK)));
    public static final EntityType<TableSeatEntity> SEAT =
            Registry.register(
                    BuiltInRegistries.ENTITY_TYPE,
                    id("seat"),
                    EntityType.Builder.<TableSeatEntity>of(TableSeatEntity::new, MobCategory.MISC)
                            .sized(.5f, .1f)
                            .noSummon()
                            .clientTrackingRange(8)
                            .updateInterval(1)
                            .build(ResourceKey.create(Registries.ENTITY_TYPE, id("seat"))));
    public static final BlockEntityType<TableBlockEntity> TABLE_ENTITY =
            Registry.register(
                    BuiltInRegistries.BLOCK_ENTITY_TYPE,
                    id("table"),
                    FabricBlockEntityTypeBuilder.create(TableBlockEntity::new, TABLE).build());

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(ID, path);
    }

    private static TableBlock register(String name) {
        ResourceKey<Block> key = ResourceKey.create(Registries.BLOCK, id(name));
        TableBlock block =
                new TableBlock(
                        BlockBehaviour.Properties.of()
                                .setId(key)
                                .strength(2.5f)
                                .sound(SoundType.WOOD)
                                .noOcclusion()
                                .pushReaction(PushReaction.BLOCK));
        Registry.register(BuiltInRegistries.BLOCK, key, block);
        Registry.register(
                BuiltInRegistries.ITEM,
                id(name),
                new BlockItem(
                        block,
                        new Item.Properties()
                                .setId(ResourceKey.create(Registries.ITEM, id(name)))
                                .useBlockDescriptionPrefix()));
        return block;
    }

    @Override
    public void onInitialize() {
        Registry.register(
                BuiltInRegistries.ITEM,
                id("control"),
                new BlockItem(
                        CONTROL,
                        new Item.Properties()
                                .setId(ResourceKey.create(Registries.ITEM, id("control")))
                                .useBlockDescriptionPrefix()));
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.END_SERVER_TICK.register(
                server -> HostedServer.get(server).tick());
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPED.register(
                HostedServer::stop);
        PayloadTypeRegistry.playC2S()
                .register(HostedPackets.Request.TYPE, HostedPackets.Request.CODEC);
        PayloadTypeRegistry.playS2C().register(HostedPackets.State.TYPE, HostedPackets.State.CODEC);
        net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.register(
                (handler, sender, server) -> {
                    if (!ServerPlayNetworking.canSend(handler.player, HostedPackets.State.TYPE))
                        handler.disconnect(
                                net.minecraft.network.chat.Component.translatable(
                                        "satori.message.mod_update_required"));
                });
        ServerPlayNetworking.registerGlobalReceiver(
                HostedPackets.Request.TYPE,
                (packet, context) ->
                        HostedServer.get(context.player().level().getServer())
                                .request(context.player(), packet));
        ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.FUNCTIONAL_BLOCKS)
                .register(
                        entries -> {
                            entries.accept(TABLE);
                            entries.accept(CONTROL);
                        });
        PayloadTypeRegistry.playS2C().register(TablePackets.Lobby.TYPE, TablePackets.Lobby.CODEC);
        PayloadTypeRegistry.playC2S()
                .register(TablePackets.Presence.TYPE, TablePackets.Presence.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(
                TablePackets.Presence.TYPE,
                (packet, context) -> TableLobby.announce(context.player(), packet));
        PayloadTypeRegistry.playS2C().register(TablePackets.Open.TYPE, TablePackets.Open.CODEC);
        PayloadTypeRegistry.playS2C().register(TablePackets.Linked.TYPE, TablePackets.Linked.CODEC);
        PayloadTypeRegistry.playC2S().register(TablePackets.Link.TYPE, TablePackets.Link.CODEC);
        PayloadTypeRegistry.playC2S().register(TablePackets.Leave.TYPE, TablePackets.Leave.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(
                TablePackets.Leave.TYPE,
                (packet, context) -> {
                    if (context.player().getVehicle() instanceof TableSeatEntity)
                        context.player().stopRiding();
                });
        ServerPlayNetworking.registerGlobalReceiver(
                TablePackets.Link.TYPE,
                (packet, context) -> TableLinkHandler.handle(context.player(), packet));
    }

    public static ChairBlock chair() {
        return CHAIR;
    }

    public static ControlBlock control() {
        return CONTROL;
    }

    public static java.nio.file.Path configPath() {
        return net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir();
    }

    public static void hostedState(ServerPlayer player, HostedPackets.State packet) {
        ServerPlayNetworking.send(player, packet);
    }

    public static EntityType<TableSeatEntity> seatEntityType() {
        return SEAT;
    }

    public static TableBlock table() {
        return TABLE;
    }

    public static BlockEntityType<TableBlockEntity> tableEntityType() {
        return TABLE_ENTITY;
    }

    public static void openTable(ServerPlayer player, TablePackets.Open packet) {
        ServerPlayNetworking.send(player, packet);
    }

    public static void tableLobby(ServerPlayer player, TablePackets.Lobby packet) {
        ServerPlayNetworking.send(player, packet);
    }

    public static void tableLinked(ServerPlayer player, TablePackets.Linked packet) {
        ServerPlayNetworking.send(player, packet);
    }
}
