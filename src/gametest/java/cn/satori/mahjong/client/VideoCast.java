package cn.satori.mahjong.client;

import cn.satori.mahjong.*;

import com.mojang.authlib.GameProfile;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;

import java.util.*;

/** Real seated Minecraft entities used as filming actors, never as AI-seat substitutes. */
final class VideoCast {
    private final List<Entity> entities = new ArrayList<>();

    void seat(Minecraft mc, BlockPos center, boolean multiplayer) {
        seat(mc, center, multiplayer ? List.of(0, 1, 2, 3) : List.of(0));
    }

    void seat(Minecraft mc, BlockPos center, List<Integer> sides) {
        for (var entity : entities) entity.discard();
        entities.clear();
        String[] names = {"Akari", "Ren", "Haru", "Yui"};
        for (int side : sides) {
            var chair = new TableSeatEntity(SatoriMahjong.seatEntityType(), mc.level);
            chair.configure(center, side);
            chair.setId(-500 - side);
            mc.level.addEntity(chair);
            var player =
                    new RemotePlayer(
                            mc.level, new GameProfile(new UUID(0, 901 + side), names[side]));
            player.setId(-600 - side);
            player.setPos(chair.position());
            player.setYRot(180 + side * 90);
            player.setYHeadRot(player.getYRot());
            player.setYBodyRot(player.getYRot());
            mc.level.addEntity(player);
            if (!player.startRiding(chair))
                throw new IllegalStateException("Filming actor not seated");
            chair.positionRider(player);
            SeatGeometryChecks.verify(player, chair);
            player.setOldPosAndRot();
            entities.add(player);
            entities.add(chair);
        }
    }
}
