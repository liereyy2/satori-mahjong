package cn.satori.mahjong.client;

import cn.satori.mahjong.*;
import cn.satori.mahjong.protocol.*;

import com.google.gson.*;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Isolated real-client smoke test. It never contacts Satori, sends mail or invokes AI. */
public final class TableGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        context.setScreen(() -> new AccountScreen(SatoriClient.TABLES.active()));
        context.waitTicks(2);
        context.takeScreenshot("satori-account");
        CompletableFuture<Void> language =
                context.computeOnClient(
                        mc -> {
                            mc.getLanguageManager().setSelected("zh_cn");
                            mc.options.languageCode = "zh_cn";
                            return mc.reloadResourcePacks();
                        });
        context.waitFor(mc -> language.isDone() && mc.getOverlay() == null);
        context.waitTicks(2);
        context.takeScreenshot("satori-account-zh");
        checkWidgets(context);
        AccountLifecycleTest.run(context);
        CreateScreenInteractionTest.run(context);
        testScreens(context);
        context.runOnClient(
                mc -> {
                    SecretEditBox secret =
                            new SecretEditBox(
                                    mc.font,
                                    0,
                                    0,
                                    100,
                                    net.minecraft.network.chat.Component.literal("Password"));
                    secret.setValue("must-not-be-narrated");
                    require(
                            !secret.createNarrationMessage()
                                    .getString()
                                    .contains("must-not-be-narrated"),
                            "Secret exposed in narration");
                });
        context.setScreen(() -> null);
        // A headless display has no window manager to guarantee focus during world loading.
        context.runOnClient(mc -> mc.options.pauseOnLostFocus = false);
        BlockPos center = new BlockPos(0, -60, 0);
        UUID game = new UUID(0, 99);
        try (var world = context.worldBuilder().create()) {
            world.getClientWorld().waitForChunksRender();
            world.getServer().runCommand("gamemode creative @a");
            context.waitTick();
            world.getServer().runCommand("time set noon");
            context.waitTick();
            world.getServer()
                    .runOnServer(
                            server -> {
                                var level = server.overworld();
                                level.setBlockAndUpdate(
                                        center, SatoriMahjong.table().defaultBlockState());
                                require(
                                        !TableBlock.complete(level, center),
                                        "Incomplete table accepted");
                                for (int x = -1; x <= 1; x++)
                                    for (int z = -1; z <= 1; z++)
                                        if (x != 0 || z != 0)
                                            level.setBlockAndUpdate(
                                                    center.offset(x, 0, z), TableBlock.part(x, z));
                                require(
                                        TableBlock.complete(level, center),
                                        "Complete table rejected");
                                var table = (TableBlockEntity) level.getBlockEntity(center);
                                UUID builder =
                                        server.getPlayerList().getPlayers().getFirst().getUUID();
                                table.setBuilder(builder);
                                table.link(game.toString());
                                require(
                                        table.canEdit(builder) && !table.canEdit(UUID.randomUUID()),
                                        "World ownership invalid");
                                var sync = table.getUpdateTag(level.registryAccess());
                                require(
                                        !sync.contains("builder")
                                                && !sync.contains("private")
                                                && !sync.contains("seat_token"),
                                        "Private metadata sent to MC peers");
                                level.setBlockAndUpdate(
                                        center.offset(1, 0, 1), Blocks.AIR.defaultBlockState());
                                require(
                                        !TableBlock.complete(level, center),
                                        "Broken structure accepted");
                                require(
                                        table.gameId().equals(game.toString()),
                                        "Breaking a frame erased cloud link");
                                level.setBlockAndUpdate(
                                        center, SatoriMahjong.table().defaultBlockState());
                                TableAssembly.assemble(level, center);
                                var replacement = (TableBlockEntity) level.getBlockEntity(center);
                                replacement.setBuilder(builder);
                                replacement.link(game.toString());
                            });
            context.waitTick();
            world.getServer().runCommand("tp @p 0.5 -58.8 3.7 180 34");
            context.waitTicks(5);
            context.runOnClient(
                    mc -> {
                        mc.player.getAbilities().flying = true;
                        mc.player.setNoGravity(true);
                        SatoriClient.TABLES.open(
                                new TablePackets.Open(center, game.toString(), true));
                        mc.setScreen(null);
                        CloudView view = new CloudView(fixture(), game, 0);
                        var controller = SatoriClient.TABLES.active();
                        controller.view = view;
                        controller.scene.update(view, false, System.nanoTime());
                        for (int seat = 1; seat < 4; seat++)
                            require(
                                    view.hand(seat).stream().allMatch(id -> id == 37),
                                    "Other concealed hand leaked");
                        mc.options.hideGui = true;
                    });
            world.getClientWorld().waitForChunksRender();
            context.waitTicks(3);
            context.takeScreenshot("satori-table-own-seat");
            world.getServer().runCommand("tp @p 0.5 -58.8 -2.7 0 34");
            context.waitTicks(5);
            context.takeScreenshot("satori-table-opponent-seat");
            ElectricTablePreview.run(context, center, game);
            context.runOnClient(
                    mc -> {
                        var view = SatoriClient.TABLES.active().view;
                        require(
                                view.viewerSeat == 0
                                        && view.hand(2).stream().allMatch(id -> id == 37),
                                "Walking changed private seat");
                        SatoriClient.TABLES.active().closeGame();
                    });
            ElectricTablePreview.armDisconnect(context, center);
        }
        context.runOnClient(
                mc -> require(!SeatedCamera.active(), "World disconnect retained table camera"));
    }

    private static void testScreens(ClientGameTestContext context) {
        try (LocalAccountFixture fixture = new LocalAccountFixture()) {
            fixture.api.session().get(5, TimeUnit.SECONDS);
            ClientController controller = new ClientController(fixture.api, fixture.config);
            controller.dimension = SatoriClient.TABLES.active().dimension;
            controller.table = SatoriClient.TABLES.active().table;
            controller.message = "logged_in";
            controller.table = new TablePackets.Open(new BlockPos(0, -60, 0), "", true);
            context.setScreen(() -> new CreateScreen(controller));
            context.waitTicks(2);
            checkWidgets(context);
            context.takeScreenshot("satori-create");
            JsonObject session =
                    JsonParser.parseString("{\"seat\":0,\"seat_token\":\"test\"}")
                            .getAsJsonObject();
            controller.game =
                    new GameConnection(
                            fixture.api, new UUID(0, 99), session, (v, e) -> {}, status -> {});
            controller.view = new CloudView(fixture(), new UUID(0, 99), 0);
            controller.connection = "connected";
            controller.message = "joined";
            context.setScreen(() -> new TableScreen(controller));
            context.waitTicks(2);
            checkWidgets(context);
            context.takeScreenshot("satori-actions");
            context.setScreen(() -> null);
            controller.closeGame();
        } catch (Exception error) {
            throw new RuntimeException(error);
        }
    }

    private static void checkWidgets(ClientGameTestContext context) {
        context.runOnClient(
                mc -> {
                    Screen screen = mc.screen;
                    for (var child : screen.children()) {
                        if (!(child instanceof AbstractWidget widget) || !widget.visible) continue;
                        require(
                                widget.getX() >= 0
                                        && widget.getY() >= 0
                                        && widget.getRight() <= screen.width
                                        && widget.getBottom() <= screen.height,
                                "Widget clipped outside screen: "
                                        + widget.getMessage().getString());
                    }
                });
    }

    private static JsonObject fixture() {
        try (InputStream stream = TableGameTest.class.getResourceAsStream("/cloud-view.json")) {
            return JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                    .getAsJsonObject();
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
