package cn.satori.mahjong.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

class RiichiHelpTest {
    private JsonObject language(String locale) throws Exception {
        try (var stream =
                        Objects.requireNonNull(
                                getClass()
                                        .getResourceAsStream(
                                                "/assets/satori_mahjong/lang/"
                                                        + locale
                                                        + ".json"));
                var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    @Test
    void everyRulebookEntryHasChineseAndEnglishWithoutOrphanedOrDuplicateKeys() throws Exception {
        var keys =
                java.util.stream.Stream.concat(
                                RiichiHelp.translationKeys().stream(),
                                SichuanHelp.translationKeys().stream())
                        .toList();
        assertEquals(keys.size(), new HashSet<>(keys).size());
        for (var locale : List.of("zh_cn", "en_us", "zh_hk", "zh_tw")) {
            var text = language(locale);
            for (String key : keys) {
                assertTrue(text.has(key), locale + ": missing " + key);
                assertFalse(text.get(key).getAsString().isBlank(), locale + ": empty " + key);
            }
            for (String key : text.keySet()) {
                if (key.startsWith("satori.help.")
                        && !key.equals("satori.help.title")
                        && (key.endsWith(".body") || key.endsWith(".title")))
                    assertTrue(keys.contains(key), "Unreachable content: " + key);
            }
        }
    }

    @Test
    void allStandardYakuAndYakumanAreReachableAlongsideTheThreeCriticalChecks() {
        assertEquals(26, RiichiHelp.NORMAL_YAKU.size());
        assertEquals(12, RiichiHelp.YAKUMAN.size());
        assertTrue(
                RiichiHelp.NORMAL_YAKU.containsAll(
                        List.of("yakuhai", "chankan", "shousangen", "ryanpeikou")));
        assertTrue(
                RiichiHelp.YAKUMAN.containsAll(
                        List.of("tenhou", "chiihou", "shousuushi", "daisuushi", "suukantsu")));
        assertEquals(40, RiichiHelp.Page.YAKU.entries().size());
        assertEquals(
                List.of("basics.win", "basics.dora", "basics.furiten"),
                RiichiHelp.Page.BASICS.entries().subList(0, 3).stream()
                        .map(RiichiHelp.Entry::id)
                        .toList());
        assertTrue(RiichiHelp.Page.values().length == 5);
    }
}
