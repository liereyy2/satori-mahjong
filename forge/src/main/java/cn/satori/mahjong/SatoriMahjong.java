package cn.satori.mahjong;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.PushReaction;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

/** Native Forge registration; the shared world/UI sources compile against these typed accessors. */
@Mod(SatoriMahjong.ID)
public final class SatoriMahjong {
    public static final String ID = "satori_mahjong";
    private static final DeferredRegister<Block> BLOCKS =
            DeferredRegister.create(Registries.BLOCK, ID);
    private static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(Registries.ITEM, ID);
    private static final DeferredRegister<BlockEntityType<?>> ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, ID);
    private static final DeferredRegister<EntityType<?>> SEAT_TYPES =
            DeferredRegister.create(Registries.ENTITY_TYPE, ID);
    private static final RegistryObject<ChairBlock> CHAIR =
            BLOCKS.register(
                    "chair",
                    () ->
                            new ChairBlock(
                                    BlockBehaviour.Properties.of()
                                            .setId(BLOCKS.key("chair"))
                                            .strength(2.5f)
                                            .noOcclusion()
                                            .sound(SoundType.WOOD)
                                            .pushReaction(PushReaction.BLOCK)));
    private static final RegistryObject<EntityType<TableSeatEntity>> SEAT =
            SEAT_TYPES.register(
                    "seat",
                    () ->
                            EntityType.Builder.<TableSeatEntity>of(
                                            TableSeatEntity::new, MobCategory.MISC)
                                    .sized(.5f, .1f)
                                    .noSummon()
                                    .clientTrackingRange(8)
                                    .updateInterval(1)
                                    .build(SEAT_TYPES.key("seat")));
    private static final RegistryObject<TableBlock> TABLE = register("table");
    private static final RegistryObject<ControlBlock> CONTROL =
            BLOCKS.register(
                    "control",
                    () ->
                            new ControlBlock(
                                    BlockBehaviour.Properties.of()
                                            .setId(BLOCKS.key("control"))
                                            .strength(3f)
                                            .sound(SoundType.METAL)));
    private static final RegistryObject<Item> CONTROL_ITEM =
            ITEMS.register(
                    "control",
                    () ->
                            new BlockItem(
                                    CONTROL.get(),
                                    new Item.Properties()
                                            .setId(ITEMS.key("control"))
                                            .useBlockDescriptionPrefix()));
    private static final RegistryObject<BlockEntityType<TableBlockEntity>> TABLE_ENTITY =
            ENTITIES.register(
                    "table",
                    () ->
                            new BlockEntityType<>(
                                    TableBlockEntity::new, java.util.Set.of(TABLE.get())));

    public SatoriMahjong(FMLJavaModLoadingContext context) {
        net.minecraftforge.event.TickEvent.ServerTickEvent.Post.BUS.addListener(
                event -> HostedServer.get(event.server()).tick());
        net.minecraftforge.event.server.ServerStoppedEvent.BUS.addListener(
                event -> HostedServer.stop(event.getServer()));
        var bus = context.getModBusGroup();
        BLOCKS.register(bus);
        ITEMS.register(bus);
        ENTITIES.register(bus);
        SEAT_TYPES.register(bus);
        BuildCreativeModeTabContentsEvent.BUS.addListener(
                event -> {
                    if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
                        event.accept(table());
                        event.accept(control());
                    }
                });
        ForgeTableNetwork.initialize();
    }

    private static RegistryObject<TableBlock> register(String name) {
        var block =
                BLOCKS.register(
                        name,
                        () ->
                                new TableBlock(
                                        BlockBehaviour.Properties.of()
                                                .setId(BLOCKS.key(name))
                                                .strength(2.5f)
                                                .sound(SoundType.WOOD)
                                                .noOcclusion()
                                                .pushReaction(PushReaction.BLOCK)));
        ITEMS.register(
                name,
                () ->
                        new BlockItem(
                                block.get(),
                                new Item.Properties()
                                        .setId(ITEMS.key(name))
                                        .useBlockDescriptionPrefix()));
        return block;
    }

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(ID, path);
    }

    public static ChairBlock chair() {
        return CHAIR.get();
    }

    public static ControlBlock control() {
        return CONTROL.get();
    }

    public static java.nio.file.Path configPath() {
        return net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get();
    }

    public static void hostedState(ServerPlayer player, HostedPackets.State packet) {
        ForgeTableNetwork.hosted(player, packet);
    }

    public static EntityType<TableSeatEntity> seatEntityType() {
        return SEAT.get();
    }

    public static TableBlock table() {
        return TABLE.get();
    }

    public static BlockEntityType<TableBlockEntity> tableEntityType() {
        return TABLE_ENTITY.get();
    }

    public static void openTable(ServerPlayer player, TablePackets.Open packet) {
        ForgeTableNetwork.open(player, packet);
    }

    public static void tableLobby(ServerPlayer player, TablePackets.Lobby packet) {
        ForgeTableNetwork.lobby(player, packet);
    }

    public static void tableLinked(ServerPlayer player, TablePackets.Linked packet) {
        ForgeTableNetwork.linked(player, packet);
    }
}
