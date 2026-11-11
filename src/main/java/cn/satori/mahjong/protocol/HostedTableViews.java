package cn.satori.mahjong.protocol;

import com.google.gson.*;

import java.util.*;

/** Select one physical recipient's role before crossing the MC network. */
public final class HostedTableViews {
    private HostedTableViews() {}

    public record Access(int role, long revision) {}

    /** Rust retains match participation when a player leaves their physical chair. */
    public static Access access(JsonObject table, UUID player, int physicalSeat) {
        if (table == null) return new Access(physicalSeat < 0 ? 4 : physicalSeat, 0);
        if (!table.has("render_stream")) throw new CloudView.ServerUpdateRequired();
        if (table.get("render_stream").isJsonNull())
            return new Access(physicalSeat < 0 ? 4 : physicalSeat, 0);
        var stream = table.getAsJsonObject("render_stream");
        if (!stream.has("participants") || !stream.has("participant_revision"))
            throw new CloudView.ServerUpdateRequired();
        var members = stream.getAsJsonObject("participants");
        String id = player.toString();
        int role = members.has(id) ? members.get(id).getAsInt() : 4;
        // Before the roster commit, a physical join can only restrict the view.
        if (physicalSeat >= 0 && role == 4) role = 5;
        if (role < 0 || role > 5) throw new IllegalArgumentException("invalid_render_role");
        return new Access(role, role == 4 ? 0 : stream.get("participant_revision").getAsLong());
    }

    public static JsonObject packet(JsonObject table, int seat, boolean changedRole) {
        if (seat < 0 || seat > 5) throw new IllegalArgumentException("invalid_render_role");
        var packet = new JsonObject();
        packet.add("view", JsonNull.INSTANCE);
        packet.addProperty("reset", true);
        packet.add("updates", new JsonArray());
        if (table == null) return packet;
        if (!table.has("render_stream")) throw new CloudView.ServerUpdateRequired();
        if (table.get("render_stream").isJsonNull()) return packet;
        var stream = table.getAsJsonObject("render_stream");
        packet.add("view", stream.getAsJsonArray("views").get(seat).deepCopy());
        boolean reset = changedRole || stream.get("reset").getAsBoolean();
        packet.addProperty("reset", reset);
        if (!reset) {
            var updates = packet.getAsJsonArray("updates");
            for (var raw : stream.getAsJsonArray("updates")) {
                var frame = raw.getAsJsonObject();
                var update = new JsonObject();
                update.add("from_cursor", frame.get("from_cursor"));
                update.add("cursor", frame.get("cursor"));
                update.add("view", frame.getAsJsonArray("views").get(seat).deepCopy());
                update.add("events", frame.getAsJsonArray("events").get(seat).deepCopy());
                updates.add(update);
            }
        }
        return packet;
    }

    /** One transition per native payload keeps catch-up batches within the channel limit. */
    public static List<JsonObject> packets(JsonObject table, int seat, boolean changedRole) {
        var selected = packet(table, seat, changedRole);
        var frames = selected.getAsJsonArray("updates");
        if (selected.get("reset").getAsBoolean() || frames.isEmpty()) return List.of(selected);
        var packets = new ArrayList<JsonObject>();
        for (var raw : frames) {
            var frame = raw.getAsJsonObject();
            var packet = new JsonObject();
            packet.add("view", frame.get("view").deepCopy());
            packet.addProperty("reset", false);
            var updates = new JsonArray();
            updates.add(frame.deepCopy());
            packet.add("updates", updates);
            packets.add(packet);
        }
        return List.copyOf(packets);
    }

    public static JsonObject publicState(JsonObject table) {
        var state = new JsonObject();
        for (var field : table.entrySet())
            if (!field.getKey().equals("render_stream"))
                state.add(field.getKey(), field.getValue().deepCopy());
        return state;
    }
}
