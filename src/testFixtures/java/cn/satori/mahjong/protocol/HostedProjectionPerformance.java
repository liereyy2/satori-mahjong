package cn.satori.mahjong.protocol;

import com.google.gson.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Separate CPU benchmark of metadata + one observer update, never a full TPS measurement. */
public final class HostedProjectionPerformance {
    private static volatile long consumed;

    private static long batch(JsonObject table, int tables) {
        long bytes = 0;
        for (int i = 0; i < tables; i++) {
            for (int seat = 0; seat < 4; seat++)
                bytes += HostedTableViews.publicState(table).toString().length();
            for (var packet : HostedTableViews.packets(table, 4, false))
                bytes += packet.toString().length();
        }
        return bytes;
    }

    public static void main(String[] args) throws Exception {
        JsonObject source;
        try (var reader =
                new InputStreamReader(
                        Objects.requireNonNull(
                                HostedProjectionPerformance.class.getResourceAsStream(
                                        "/table-stream-examples.json")),
                        java.nio.charset.StandardCharsets.UTF_8)) {
            source = JsonParser.parseReader(reader).getAsJsonObject();
        }
        var stream = source.getAsJsonObject("opening");
        var table = new JsonObject();
        table.addProperty("phase", "playing");
        table.add(
                "game_id", stream.getAsJsonArray("views").get(4).getAsJsonObject().get("game_id"));
        table.add(
                "seats",
                stream.getAsJsonArray("views")
                        .get(4)
                        .getAsJsonObject()
                        .getAsJsonObject("public")
                        .get("seats"));
        table.add("render_stream", stream);
        for (int i = 0; i < 200; i++) consumed = batch(table, 1);
        var report = new JsonObject();
        report.addProperty(
                "scope",
                "CPU only: per table four public metadata payloads and one actual Rust observer"
                        + " update batch; no HTTP or network delivery");
        var cases = new JsonArray();
        report.add("cases", cases);
        for (int tables : new int[] {1, 4, 16}) {
            double[] times = new double[100];
            long bytes = 0;
            for (int i = 0; i < times.length; i++) {
                long begin = System.nanoTime();
                bytes = batch(table, tables);
                times[i] = (System.nanoTime() - begin) / 1e6;
                consumed = bytes;
            }
            Arrays.sort(times);
            var row = new JsonObject();
            row.addProperty("tables", tables);
            row.addProperty("samples", times.length);
            row.addProperty("bytes", bytes);
            row.addProperty("mean_ms", Arrays.stream(times).average().orElseThrow());
            row.addProperty("p50_ms", times[49]);
            row.addProperty("p95_ms", times[94]);
            cases.add(row);
        }
        Files.writeString(
                Path.of(args[0]),
                new GsonBuilder().setPrettyPrinting().create().toJson(report) + "\n");
        System.out.println(report);
    }
}
