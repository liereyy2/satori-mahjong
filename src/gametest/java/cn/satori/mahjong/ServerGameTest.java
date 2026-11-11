package cn.satori.mahjong;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

/** Dedicated-server verification is packaged only in the isolated test mod. */
public final class ServerGameTest implements DedicatedServerModInitializer {
    @Override
    public void onInitializeServer() {
        ServerLifecycleEvents.SERVER_STARTED.register(
                server -> {
                    Path report = Path.of(System.getProperty("satori.smoke.report"));
                    try {
                        var level = server.overworld();
                        var center = new BlockPos(0, 80, 0);
                        level.getChunkAt(center);
                        level.destroyBlock(center, false);
                        for (var part : TableAssembly.footprint(center)) {
                            level.setBlockAndUpdate(part, Blocks.AIR.defaultBlockState());
                            level.setBlockAndUpdate(part.above(), Blocks.AIR.defaultBlockState());
                        }
                        level.setBlockAndUpdate(center, SatoriMahjong.table().defaultBlockState());
                        for (int x = -1; x <= 1; x++)
                            for (int z = -1; z <= 1; z++)
                                if (x != 0 || z != 0)
                                    level.setBlockAndUpdate(
                                            center.offset(x, 0, z), TableBlock.part(x, z));
                        level.setBlockAndUpdate(
                                TableSeating.chair(center, 0), Blocks.STONE.defaultBlockState());
                        if (TableSeating.furnish(level, center))
                            throw new AssertionError("Occupied furniture space overwritten");
                        level.setBlockAndUpdate(
                                TableSeating.chair(center, 0), Blocks.AIR.defaultBlockState());
                        TableLightingChecks.verify(level, center);
                        var table = (TableBlockEntity) level.getBlockEntity(center);
                        if (!TableSeating.furnish(level, center)
                                || !SatoriMahjong.seatEntityType().canSerialize()
                                || new TableSeatEntity(SatoriMahjong.seatEntityType(), level)
                                        .shouldBeSaved())
                            throw new AssertionError(
                                    "Four chairs and native rideable non-persistent seat");
                        UUID owner = new UUID(0, 1), game = new UUID(0, 99);
                        table.setBuilder(owner);
                        table.link(game.toString());
                        var restored =
                                (TableBlockEntity)
                                        BlockEntity.loadStatic(
                                                center,
                                                table.getBlockState(),
                                                table.saveWithFullMetadata(level.registryAccess()),
                                                level.registryAccess());
                        if (!TableBlock.complete(level, center)
                                || restored == null
                                || !restored.canEdit(owner)
                                || !restored.gameId().equals(game.toString())
                                || !table.getUpdateTag(level.registryAccess())
                                        .keySet()
                                        .equals(Set.of("game_id", "cloud_table_id")))
                            throw new AssertionError(
                                    "Dedicated server registration, structure or private NBT"
                                            + " failed");
                        Files.writeString(
                                report,
                                "{\"status\":\"passed\",\"detail\":\"Fabric production JAR"
                                        + " dedicated server, table and NBT\"}\n");
                    } catch (Throwable error) {
                        error.printStackTrace();
                        try {
                            Files.writeString(report, "{\"status\":\"failed\"}\n");
                        } catch (Exception failedReport) {
                            throw new IllegalStateException(failedReport);
                        }
                    } finally {
                        server.halt(false);
                    }
                });
    }
}
