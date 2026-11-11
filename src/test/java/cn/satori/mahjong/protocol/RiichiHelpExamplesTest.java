package cn.satori.mahjong.protocol;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;

import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

class RiichiHelpExamplesTest {
    @Test
    void everyYakuHasACompleteIllustrationAndCountedAndMultipleYakumanAreSeparate() {
        var illustrated =
                RiichiHelp.Page.YAKU.entries().stream()
                        .filter(e -> !e.id().equals("yaku.read"))
                        .toList();
        for (var entry : illustrated)
            assertFalse(RiichiHelpExamples.forEntry(entry).isEmpty(), entry.id());
        assertEquals(40, RiichiHelpExamples.all().size());
        var examples = RiichiHelpExamples.all().values().stream().flatMap(List::stream).toList();
        assertEquals(41, examples.size());
        assertEquals(
                41,
                new HashSet<>(examples.stream().map(RiichiHelpExamples.Example::id).toList())
                        .size());
        assertEquals(2, RiichiHelpExamples.forEntry(new RiichiHelp.Entry("yaku.counted")).size());
        assertTrue(RiichiHelpExamples.forEntry(new RiichiHelp.Entry("yaku.read")).isEmpty());
    }

    @Test
    void illustrationsWrapWithoutClippingOrOverlappingTilesAtSmallAndLargeWidths() {
        for (var examples : RiichiHelpExamples.all().values())
            for (var example : examples) {
                for (int width : new int[] {216, 242, 320, 562, 710}) {
                    var layout = RiichiExampleLayout.layout(example, width);
                    assertEquals(
                            example.groups().stream().mapToInt(g -> g.tiles().size()).sum(),
                            layout.tiles().size());
                    assertEquals(
                            example.winMethod() == RiichiHelpExamples.WinMethod.INITIAL ? 0 : 1,
                            layout.tiles().stream()
                                    .filter(RiichiExampleLayout.Tile::winning)
                                    .count());
                    for (var tile : layout.tiles()) {
                        assertTrue(tile.x() >= 2 && tile.y() >= 2, example.id());
                        assertTrue(tile.x() + tile.width() + 2 <= width, example.id());
                        assertTrue(tile.y() + tile.height() + 2 <= layout.height(), example.id());
                        for (var other : layout.tiles())
                            if (tile != other)
                                assertTrue(
                                        tile.x() + tile.width() <= other.x()
                                                || other.x() + other.width() <= tile.x()
                                                || tile.y() + tile.height() <= other.y()
                                                || other.y() + other.height() <= tile.y(),
                                        example.id());
                    }
                }
            }
    }

    @Test
    void annotationsAreTranslatedAndHeadingsUseDirectStatements() throws Exception {
        for (String locale : List.of("zh_cn", "en_us", "zh_hk", "zh_tw")) {
            try (var input =
                            Objects.requireNonNull(
                                    getClass()
                                            .getResourceAsStream(
                                                    "/assets/satori_mahjong/lang/"
                                                            + locale
                                                            + ".json"));
                    var reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
                var words = JsonParser.parseReader(reader).getAsJsonObject();
                for (var examples : RiichiHelpExamples.all().values())
                    for (var e : examples) {
                        assertTrue(e.labelKey().startsWith("satori.help.example.note."), e.id());
                        assertFalse(words.get(e.labelKey()).getAsString().isBlank(), e.id());
                        assertTrue(words.has(e.winMethod().labelKey()));
                        for (var group : e.groups())
                            if (group.kind() != RiichiHelpExamples.Kind.HAND)
                                assertTrue(words.has(group.kind().labelKey()));
                    }
                if (locale.startsWith("zh_")) {
                    for (var e : words.entrySet())
                        if (e.getKey().startsWith("satori.help.")) {
                            String text = e.getValue().getAsString();
                            for (String phrase : List.of("先看这里", "怎样", "怎么", "如何", "什么时候", "？"))
                                assertFalse(text.contains(phrase), e.getKey());
                        }
                }
            }
        }
    }
}
