package cn.satori.mahjong;

import cn.satori.mahjong.protocol.ControlConfig;

import com.google.gson.JsonObject;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.fml.common.Mod;

import java.nio.file.*;

/** Dedicated loader validation using a loopback transport and disposable world. */
@Mod("satori_mahjong_smoke")
public final class ForgeSmoke {
    private static boolean joined;

    public ForgeSmoke() {
        ServerStartedEvent.BUS.addListener(
                event -> {
                    var server = event.getServer();
                    if (!Boolean.getBoolean("satori.smoke.reload")) {
                        HostedNativeFixture.install(server);
                        return;
                    }
                    try {
                        HostedNativeFixture.transport(server, false);
                        var level = server.overworld();
                        level.getChunkAt(HostedNativeFixture.CENTER);
                        var table =
                                (TableBlockEntity) level.getBlockEntity(HostedNativeFixture.CENTER);
                        require(
                                table != null && table.gameId().isEmpty(),
                                "Finished table restored an active game");
                        String expected =
                                Files.readString(
                                        SatoriMahjong.configPath().resolve("fixture-table-id.txt"));
                        require(
                                table.cloudTableId().toString().equals(expected),
                                "Persistent cloud table ID changed");
                        require(
                                !new cn.satori.mahjong.protocol.HostedReleaseQueue(
                                                SatoriMahjong.configPath()
                                                        .resolve("satori-table-releases.json"))
                                        .pending()
                                        .contains(table.cloudTableId()),
                                "World unload incorrectly queued a table release");
                        var config =
                                new ControlConfig(
                                        SatoriMahjong.configPath()
                                                .resolve("satori-mahjong-control.json"));
                        require(
                                config.username().equals("shared@example.test")
                                        && config.keyId() != null,
                                "Shared Control configuration did not persist");
                        report(
                                "passed",
                                "Dedicated world reload, cloud table identity and Control config"
                                        + " persisted");
                        server.halt(false);
                    } catch (Throwable e) {
                        fail(server, e);
                    }
                });
        PlayerEvent.PlayerLoggedInEvent.BUS.addListener(
                event -> {
                    if (!(event.getEntity() instanceof ServerPlayer p)) return;
                    var server = p.level().getServer();
                    try {
                        server.getPlayerList().deop(p.nameAndId());
                        p.teleportTo(3.5, -59, 2.5);
                        var denied = new JsonObject();
                        denied.addProperty("kind", "login");
                        denied.addProperty("username", "unauthorized@example.test");
                        denied.addProperty("password", "not-allowed");
                        HostedServer.get(server)
                                .request(
                                        p,
                                        new HostedPackets.Request(
                                                p.level().dimension(),
                                                HostedNativeFixture.CONTROL,
                                                true,
                                                denied.toString()));
                        require(
                                !HostedNativeFixture.http.authenticated,
                                "Non-OP changed shared credentials");
                        server.getCommands()
                                .performPrefixedCommand(
                                        server.createCommandSourceStack(),
                                        "op " + p.getGameProfile().name());
                        server.getCommands()
                                .performPrefixedCommand(
                                        server.createCommandSourceStack(), "gamemode creative @a");
                        var table =
                                (TableBlockEntity)
                                        p.level().getBlockEntity(HostedNativeFixture.CENTER);
                        require(
                                table.getUpdateTag(p.level().registryAccess())
                                        .keySet()
                                        .equals(java.util.Set.of("game_id", "cloud_table_id")),
                                "Private world metadata synchronized");
                        TableLightingChecks.verify(
                                (net.minecraft.server.level.ServerLevel) p.level(),
                                HostedNativeFixture.CENTER);
                        HostedServer.get(server).openControl(p, HostedNativeFixture.CONTROL);
                        joined = true;
                    } catch (Throwable e) {
                        fail(server, e);
                    }
                });
        PlayerEvent.PlayerLoggedOutEvent.BUS.addListener(
                event -> {
                    if (!joined || !(event.getEntity() instanceof ServerPlayer p)) return;
                    var server = p.level().getServer();
                    try {
                        require(
                                HostedNativeFixture.http.failure == null,
                                "Loopback protocol failure: " + HostedNativeFixture.http.failure);
                        require(
                                server.overworld().getBlockEntity(HostedNativeFixture.CENTER)
                                        == null,
                                "Destroyed table survived native lifecycle check");
                        server.overworld()
                                .setBlockAndUpdate(
                                        HostedNativeFixture.CENTER,
                                        SatoriMahjong.table().defaultBlockState());
                        TableAssembly.assemble(server.overworld(), HostedNativeFixture.CENTER);
                        var table =
                                (TableBlockEntity)
                                        server.overworld()
                                                .getBlockEntity(HostedNativeFixture.CENTER);
                        require(
                                table.gameId().isEmpty(),
                                "Replacement table inherited an old game");
                        Files.writeString(
                                SatoriMahjong.configPath().resolve("fixture-table-id.txt"),
                                table.cloudTableId().toString());
                        server.saveEverything(false, true, true);
                        HostedServer.stop(server);
                        HostedNativeFixture.http.close();
                        report(
                                "passed",
                                "Native Control permission checks, shared account,"
                                        + " ready/start/finish and disconnect");
                        server.halt(false);
                    } catch (Throwable e) {
                        fail(server, e);
                    }
                });
    }

    public static void require(boolean ok, String detail) {
        if (!ok) throw new AssertionError(detail);
    }

    public static void report(String status, String detail) {
        try {
            Path p = Path.of(System.getProperty("satori.smoke.report"));
            Files.createDirectories(p.getParent());
            var result = new JsonObject();
            result.addProperty("status", status);
            result.addProperty("detail", detail);
            Files.writeString(p, result + "\n");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void fail(MinecraftServer server, Throwable e) {
        e.printStackTrace();
        report("failed", e.toString());
        server.halt(false);
    }
}
