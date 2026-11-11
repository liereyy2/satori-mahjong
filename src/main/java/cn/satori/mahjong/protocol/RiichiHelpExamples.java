package cn.satori.mahjong.protocol;

import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable illustrations only. Hands are checked offline by the existing Rust scorer. */
public final class RiichiHelpExamples {
    private RiichiHelpExamples() {}

    public enum Kind {
        HAND,
        CHI,
        PON,
        MINKAN,
        ANKAN;

        public boolean open() {
            return this == CHI || this == PON || this == MINKAN;
        }

        public String labelKey() {
            return "satori.help.example." + name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    public enum WinMethod {
        RON,
        TSUMO,
        INITIAL;

        public String labelKey() {
            return "satori.help.example." + name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    public record Group(Kind kind, List<Integer> tiles) {
        public Group {
            tiles = List.copyOf(tiles);
            if (tiles.isEmpty()
                    || tiles.size() > 4
                    || tiles.stream().anyMatch(t -> t < 0 || t > 36))
                throw new IllegalArgumentException("Invalid illustrated group");
        }
    }

    public record Example(
            String id,
            String labelKey,
            WinMethod winMethod,
            int winGroup,
            int winIndex,
            List<Group> groups) {
        public Example {
            groups = List.copyOf(groups);
            if (winGroup < 0
                    || winGroup >= groups.size()
                    || winIndex < 0
                    || winIndex >= groups.get(winGroup).tiles.size())
                throw new IllegalArgumentException("Invalid illustrated winning tile: " + id);
        }

        public boolean open() {
            return groups.stream().anyMatch(g -> g.kind.open());
        }
    }

    private static final Map<String, List<Example>> ENTRIES = load("riichi_examples.json");
    private static final Map<String, List<Example>> SICHUAN = load("sichuan_examples.json");

    public static Map<String, List<Example>> all() {
        return ENTRIES;
    }

    public static List<Example> forEntry(RiichiHelp.Entry entry) {
        if (entry.id().startsWith("sichuan.")) {
            if (entry.id().equals("sichuan.basics.example")
                    || (entry.id().startsWith("sichuan.yaku.")
                            && !entry.id().equals("sichuan.yaku.read")))
                return Objects.requireNonNull(
                        SICHUAN.get(entry.id()), "Missing Sichuan illustration: " + entry.id());
            return List.of();
        }
        if ((!entry.id().startsWith("yaku.") || entry.id().equals("yaku.read"))
                && !entry.id().equals("basics.example")) return List.of();
        return Objects.requireNonNull(
                ENTRIES.get(entry.id()), "Missing required hand example: " + entry.id());
    }

    private static Map<String, List<Example>> load(String resource) {
        var entries = new LinkedHashMap<String, List<Example>>();
        try (var input =
                        Objects.requireNonNull(
                                RiichiHelpExamples.class.getResourceAsStream(
                                        "/assets/satori_mahjong/help/" + resource));
                var reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            for (var entry : JsonParser.parseReader(reader).getAsJsonObject().entrySet()) {
                var examples = new ArrayList<Example>();
                for (var value : entry.getValue().getAsJsonArray()) {
                    var raw = value.getAsJsonObject();
                    var groups = new ArrayList<Group>();
                    for (var item : raw.getAsJsonArray("groups")) {
                        var group = item.getAsJsonObject();
                        var tiles = new ArrayList<Integer>();
                        group.getAsJsonArray("tiles").forEach(t -> tiles.add(t.getAsInt()));
                        groups.add(
                                new Group(
                                        Kind.valueOf(
                                                group.get("kind")
                                                        .getAsString()
                                                        .toUpperCase(java.util.Locale.ROOT)),
                                        tiles));
                    }
                    examples.add(
                            new Example(
                                    raw.get("id").getAsString(),
                                    raw.get("labelKey").getAsString(),
                                    WinMethod.valueOf(
                                            raw.get("winMethod")
                                                    .getAsString()
                                                    .toUpperCase(java.util.Locale.ROOT)),
                                    raw.get("winGroup").getAsInt(),
                                    raw.get("winIndex").getAsInt(),
                                    groups));
                }
                if (examples.isEmpty())
                    throw new IllegalArgumentException(
                            "Empty hand illustrations: " + entry.getKey());
                entries.put(entry.getKey(), List.copyOf(examples));
            }
            return java.util.Collections.unmodifiableMap(entries);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
