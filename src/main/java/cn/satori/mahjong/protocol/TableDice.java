package cn.satori.mahjong.protocol;

import java.util.*;

/** Dice geometry and a short deterministic throw; values and roller come from Rust. */
public final class TableDice {
    public record Die(int top, float x, float y, float z, float pitch, float yaw, float roll) {}

    private TableDice() {}

    public static List<Die> sample(int roller, List<Integer> values, float time) {
        if (time < 1.45f || time >= 3.9f) return List.of();
        float raw = Math.clamp((time - 1.45f) / .55f, 0, 1);
        float t = TableTransition.ease(raw);
        float angle = (float) (-roller * Math.PI / 2);
        var result = new ArrayList<Die>();
        for (int i = 0; i < 2; i++) {
            int top = values.get(i);
            float x = (i == 0 ? -.11f : .11f) + (i == 0 ? -.06f : .04f) * t;
            float z = 1.14f + (.50f + i * .025f - 1.14f) * t;
            float pitch =
                    switch (top) {
                        case 2 -> -90;
                        case 5 -> 90;
                        case 6 -> 180;
                        default -> 0;
                    };
            float roll =
                    switch (top) {
                        case 3 -> 90;
                        case 4 -> -90;
                        default -> 0;
                    };
            result.add(
                    new Die(
                            top,
                            x * (float) Math.cos(angle) - z * (float) Math.sin(angle),
                            1.055f + (float) Math.sin(raw * Math.PI) * .21f,
                            x * (float) Math.sin(angle) + z * (float) Math.cos(angle),
                            pitch + (1 - t) * 720,
                            roller * 90 + (i == 0 ? -12 : 19),
                            roll + (1 - t) * (i == 0 ? 540 : -540)));
        }
        return List.copyOf(result);
    }
}
