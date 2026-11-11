package cn.satori.mahjong.protocol;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class SichuanHelpTest {
    @Test
    void realSichuanProjectionsContainThreeSuitActionsAndCompleteWorldGlyphs() throws Exception {
        try (var input = getClass().getResourceAsStream("/sichuan-examples.json");
                var reader =
                        new java.io.InputStreamReader(
                                java.util.Objects.requireNonNull(input),
                                java.nio.charset.StandardCharsets.UTF_8)) {
            var examples = com.google.gson.JsonParser.parseReader(reader).getAsJsonObject();
            for (var entry : examples.entrySet()) {
                var raw = entry.getValue().getAsJsonObject();
                var view =
                        new CloudView(
                                raw,
                                java.util.UUID.fromString(raw.get("game_id").getAsString()),
                                0);
                var center = TableCenter.from(view);
                assertEquals("sichuan", center.ruleMode());
                assertTrue(
                        TableLayout.layout(view).stream()
                                .allMatch(t -> t.face() < 27 || t.face() == 37));
                if (entry.getKey().equals("dingque")) assertEquals(3, view.buttonActions().size());
                if (entry.getKey().equals("won")) {
                    assertTrue(center.seats().stream().anyMatch(TableCenter.Seat::won));
                    for (var tile : TableLayout.layout(view))
                        if (tile.key().startsWith("hand-") && center.seats().get(tile.seat()).won())
                            assertNotEquals(
                                    -90f,
                                    tile.pitch(),
                                    "The continuing winner keeps concealed tiles upright");
                    assertTrue(
                            TableLayout.layout(view).stream()
                                    .anyMatch(
                                            t -> t.key().startsWith("win-") && t.pitch() == -90f));
                }
            }
        }
        try (var input =
                        getClass()
                                .getResourceAsStream(
                                        "/assets/satori_mahjong/textures/gui/center-glyphs.json");
                var reader =
                        new java.io.InputStreamReader(
                                java.util.Objects.requireNonNull(input),
                                java.nio.charset.StandardCharsets.UTF_8)) {
            var glyphs =
                    com.google.gson.JsonParser.parseReader(reader)
                            .getAsJsonObject()
                            .getAsJsonObject("glyphs");
            for (char c : "第16局川麻 · 血战到底缺万缺筒缺条已和定缺中换牌中".toCharArray())
                assertTrue(glyphs.has(String.valueOf(c)), "Missing " + c);
        }
    }

    @Test
    void everyPatternHasRealTileExamplesAndEveryPageIsReachable() {
        assertEquals(15, SichuanHelp.entries(RiichiHelp.Page.YAKU).size());
        for (var entry : SichuanHelp.entries(RiichiHelp.Page.YAKU)) {
            if (entry.id().equals("sichuan.yaku.read")) continue;
            for (var sample : RiichiHelpExamples.forEntry(entry)) {
                assertTrue(
                        sample.groups().stream()
                                .flatMap(g -> g.tiles().stream())
                                .allMatch(t -> t < 27));
                for (int width : new int[] {216, 242, 562, 710}) {
                    var layout = RiichiExampleLayout.layout(sample, width);
                    for (var tile : layout.tiles())
                        assertTrue(tile.x() + tile.width() + 2 <= width, sample.id());
                }
            }
        }
        assertTrue(
                SichuanHelp.entries(RiichiHelp.Page.BASICS).subList(0, 3).stream()
                        .allMatch(RiichiHelp.Entry::highlighted));
        var keys = SichuanHelp.translationKeys();
        assertEquals(keys.size(), new HashSet<>(keys).size());
    }
}
