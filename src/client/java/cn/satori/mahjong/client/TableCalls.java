package cn.satori.mahjong.client;

import cn.satori.mahjong.SatoriMahjong;
import cn.satori.mahjong.protocol.TableScene;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvent;

/** Audible only for confirmed, currently animated events; snapshots never replay a call. */
final class TableCalls {
    private static final float CALL_VOLUME = 1.0f;
    private static final float TILE_VOLUME = 1.0f;
    private TableScene scene;
    private long last = Long.MIN_VALUE;

    void tick(TableScene current, boolean seated) {
        if (scene != current) {
            scene = current;
            last = Long.MIN_VALUE;
        }
        for (var sound : current.sounds(last, current.now())) {
            last = sound.id();
            if (seated)
                Minecraft.getInstance()
                        .getSoundManager()
                        .play(
                                SimpleSoundInstance.forUI(
                                        SoundEvent.createVariableRangeEvent(
                                                SatoriMahjong.id(sound.name())),
                                        1.0f,
                                        sound.name().startsWith("call.")
                                                ? CALL_VOLUME
                                                : TILE_VOLUME));
        }
    }
}
