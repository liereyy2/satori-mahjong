package cn.satori.mahjong.protocol;

import com.google.gson.JsonObject;

import java.util.UUID;

/** Explicit, user-visible support metadata; never serializes a controller or a cloud view. */
public record BugReport(String modVersion, String loader, String minecraftVersion, UUID gameId) {
    public String context() {
        return "SatoriMahjong "
                + modVersion
                + "\nMinecraft "
                + minecraftVersion
                + " / "
                + loader
                + "\nGame ID: "
                + (gameId == null ? "-" : gameId);
    }

    public JsonObject ticket(String title, String description) {
        String subject = "[SatoriMahjong Bug] " + title.trim();
        String body = description.trim() + "\n\n" + context();
        if (title.isBlank()
                || description.isBlank()
                || subject.length() > 256
                || body.length() > 4000) throw new IllegalArgumentException("feedback_invalid");
        var payload = new JsonObject();
        payload.addProperty("subject", subject);
        payload.addProperty("body", body);
        return payload;
    }
}
