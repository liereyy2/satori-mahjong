package cn.satori.mahjong.client;

import cn.satori.mahjong.*;
import cn.satori.mahjong.protocol.*;

import com.google.gson.*;

import java.util.*;
import java.util.function.LongSupplier;

/** Ordered, read-only world frames, independent of seat command sessions. */
final class WorldTables {
    static final class Table {
        final UUID id;
        final CloudView view;
        final TableScene scene;
        final TableRenderFeed feed;

        Table(UUID id, TableRenderFeed feed) {
            this.id = id;
            this.feed = feed;
            this.view = feed.view();
            this.scene = feed.scene();
        }
    }

    private record Highlight(UUID id, long started) {}

    private final Map<ClientTables.Address, Table> tables = new HashMap<>();
    private final Map<ClientTables.Address, Highlight> highlights = new HashMap<>();
    private LongSupplier clock = System::nanoTime;

    void clock(LongSupplier clock) {
        if (!tables.isEmpty())
            throw new IllegalStateException("Table display clock already in use");
        this.clock = Objects.requireNonNull(clock);
    }

    void accept(HostedPackets.State packet, JsonObject json) {
        var address = new ClientTables.Address(packet.dimension(), packet.pos());
        UUID id = UUID.fromString(json.get("table_id").getAsString());
        switch (json.get("kind").getAsString()) {
            case "highlight" -> {
                long now = System.nanoTime();
                highlights
                        .entrySet()
                        .removeIf(e -> now - e.getValue().started >= TableHighlight.DURATION);
                highlights.put(address, new Highlight(id, now));
                var mc = net.minecraft.client.Minecraft.getInstance();
                if (mc.screen instanceof ControlScreen) mc.setScreen(null);
            }
            case "table_view" -> {
                if (json.get("view").isJsonNull()) {
                    tables.remove(address);
                    return;
                }
                var previous = tables.get(address);
                var feed =
                        previous != null && previous.id.equals(id)
                                ? previous.feed
                                : new TableRenderFeed(clock);
                feed.accept(json);
                tables.put(address, new Table(id, feed));
            }
            default -> throw new IllegalArgumentException("unknown_world_table_packet");
        }
    }

    Table forTable(TableBlockEntity block) {
        if (block.getLevel() == null) return null;
        var table =
                tables.get(
                        new ClientTables.Address(
                                block.getLevel().dimension(), block.getBlockPos()));
        return table != null
                        && table.id.equals(block.cloudTableId())
                        && table.view.gameId.toString().equals(block.gameId())
                ? table
                : null;
    }

    boolean highlighted(TableBlockEntity block) {
        if (block.getLevel() == null) return false;
        var address = new ClientTables.Address(block.getLevel().dimension(), block.getBlockPos());
        var value = highlights.get(address);
        if (value == null || !value.id.equals(block.cloudTableId())) return false;
        long elapsed = System.nanoTime() - value.started;
        if (elapsed >= TableHighlight.DURATION) highlights.remove(address);
        return TableHighlight.visible(elapsed);
    }

    void clear() {
        tables.clear();
        highlights.clear();
    }
}
