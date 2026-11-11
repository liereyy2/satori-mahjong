package cn.satori.mahjong.client;

import com.google.gson.JsonParser;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;

import java.nio.file.*;

/** Optional initial set construction only; actual filming runs in the ordinary client. */
public final class VideoWorldPreparation implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        try {
            var directory = Path.of(System.getProperty("satori.video.directory"));
            var game =
                    JsonParser.parseString(Files.readString(directory.resolve("game.json")))
                            .getAsJsonObject()
                            .get("game_id")
                            .getAsString();
            try (var world = context.worldBuilder().create()) {
                world.getClientWorld().waitForChunksRender();
                world.getServer()
                        .runOnServer(
                                server -> {
                                    VideoRoomBuilder.build(
                                            server.overworld(), new BlockPos(0, -59, 0), game);
                                    server.getPlayerList()
                                            .getPlayers()
                                            .getFirst()
                                            .teleportTo(4.5, -56, 5.5);
                                });
                context.waitTicks(20);
            }
            var result = new com.google.gson.JsonObject();
            result.addProperty("prepared", true);
            Files.writeString(directory.resolve("world-prepared.json"), result.toString());
        } catch (Exception error) {
            throw new RuntimeException(error);
        }
    }
}
