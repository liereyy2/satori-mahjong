package cn.satori.mahjong.protocol;

import com.google.gson.*;

import java.util.*;
import java.util.function.LongSupplier;

/** Continuous table transport cursor and presentation queue; no local game rules. */
public final class TableRenderFeed {
    private CloudView view;
    private final LongSupplier clock;
    private TableScene scene;

    public TableRenderFeed() {
        this(System::nanoTime);
    }

    public TableRenderFeed(LongSupplier clock) {
        this.clock = Objects.requireNonNull(clock);
        scene = new TableScene(clock);
    }

    public CloudView view() {
        return view;
    }

    public TableScene scene() {
        return scene;
    }

    private static CloudView read(JsonObject data) {
        var next =
                new CloudView(
                        data,
                        UUID.fromString(data.get("game_id").getAsString()),
                        data.get("viewer_seat").getAsInt());
        if (next.decision() != null) throw new IllegalArgumentException("world_view_has_decision");
        return next;
    }

    public void accept(JsonObject packet) {
        var latest = read(packet.getAsJsonObject("view"));
        if (view == null
                || packet.get("reset").getAsBoolean()
                || !view.gameId.equals(latest.gameId)
                || view.viewerSeat != latest.viewerSeat) {
            scene = new TableScene(clock);
            scene.update(latest, false, scene.now());
            view = latest;
            return;
        }
        var frames = new ArrayList<CloudView>();
        var events = new ArrayList<JsonArray>();
        String cursor = view.cursor;
        long version = view.version;
        for (var raw : packet.getAsJsonArray("updates")) {
            var frame = raw.getAsJsonObject();
            var next = read(frame.getAsJsonObject("view"));
            if (!next.gameId.equals(view.gameId)
                    || next.viewerSeat != view.viewerSeat
                    || next.tableObserver() != view.tableObserver())
                throw new IllegalArgumentException("world_role_changed");
            if (next.version <= version) continue;
            if (!frame.get("from_cursor").getAsString().equals(cursor)
                    || !frame.get("cursor").getAsString().equals(next.cursor)
                    || next.version != version + 1)
                throw new IllegalArgumentException("world_cursor_gap");
            frames.add(next);
            events.add(frame.getAsJsonArray("events"));
            cursor = next.cursor;
            version = next.version;
        }
        if (!cursor.equals(latest.cursor)) throw new IllegalArgumentException("world_cursor_gap");
        for (int i = 0; i < frames.size(); i++)
            scene.append(frames.get(i), events.get(i), scene.now());
        view = latest;
    }
}
