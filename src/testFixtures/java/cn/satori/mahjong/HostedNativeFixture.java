package cn.satori.mahjong;

import cn.satori.mahjong.protocol.*;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;

import java.util.Map;

/** Installs loopback transport in the test mod, never in a distributed production entrypoint. */
public final class HostedNativeFixture {
    public static final BlockPos CENTER = new BlockPos(0, -60, 0),
            CONTROL = new BlockPos(3, -60, 1);
    public static LocalTableSessionsFixture http;

    /** Read on the server thread, after the first periodic account check has completed. */
    public static boolean initialAccountCheckComplete(MinecraftServer server) {
        try {
            var host = HostedServer.get(server);
            var busy = HostedServer.class.getDeclaredField("accountBusy");
            var nextCheck = HostedServer.class.getDeclaredField("nextCheck");
            busy.setAccessible(true);
            nextCheck.setAccessible(true);
            return !busy.getBoolean(host) && nextCheck.getLong(host) > System.nanoTime();
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException(error);
        }
    }

    public static void install(MinecraftServer server) {
        transport(server, true);
        var level = server.overworld();
        level.getChunkAt(CENTER);
        level.setBlockAndUpdate(
                CENTER, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(CENTER, SatoriMahjong.table().defaultBlockState());
        TableAssembly.assemble(level, CENTER);
        level.setBlockAndUpdate(CONTROL, SatoriMahjong.control().defaultBlockState());
    }

    @SuppressWarnings("unchecked")
    public static void transport(MinecraftServer server, boolean clean) {
        try {
            HostedServer.stop(server);
            if (clean) {
                java.nio.file.Files.deleteIfExists(
                        server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                                .resolve("data/satori-tables.json"));
                java.nio.file.Files.deleteIfExists(
                        SatoriMahjong.configPath().resolve("satori-mahjong-control.json"));
                java.nio.file.Files.deleteIfExists(
                        SatoriMahjong.configPath().resolve("satori-table-releases.json"));
            }
            http = new LocalTableSessionsFixture(true);
            http.initialAccountCheck =
                    () -> server.submit(() -> initialAccountCheckComplete(server));
            var field = HostedServer.class.getDeclaredField("INSTANCES");
            field.setAccessible(true);
            ((Map<MinecraftServer, HostedServer>) field.get(null))
                    .put(server, new HostedServer(server, http.api));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
