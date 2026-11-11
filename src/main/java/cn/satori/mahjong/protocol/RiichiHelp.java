package cn.satori.mahjong.protocol;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/** Static rulebook navigation. This catalog does not evaluate hands or own game state. */
public final class RiichiHelp {
    private RiichiHelp() {}

    public enum Page {
        BASICS("basics", "win", "dora", "furiten", "shape", "routes", "example"),
        PLAY("play", "tiles", "turn", "seats", "chi", "pon", "kan", "closed", "priority", "pass"),
        YAKU("yaku"),
        RIICHI(
                "riichi",
                "declare",
                "locked",
                "ippatsu",
                "discard_furiten",
                "temporary",
                "riichi_furiten",
                "waits"),
        SCORING(
                "scoring",
                "han_fu",
                "payments",
                "pao",
                "limits",
                "dora_cycle",
                "draw",
                "nagashi",
                "abort",
                "match");

        public final String id;
        private final List<Entry> entries;

        Page(String id, String... entries) {
            this.id = id;
            this.entries = Arrays.stream(entries).map(e -> new Entry(id + "." + e)).toList();
        }

        public String titleKey() {
            return "satori.help.tab." + id;
        }

        public List<Entry> entries() {
            return this == YAKU ? YAKU_ENTRIES : entries;
        }
    }

    public record Entry(String id) {
        public String titleKey() {
            return "satori.help." + id + ".title";
        }

        public String bodyKey() {
            return "satori.help." + id + ".body";
        }

        public boolean highlighted() {
            return id.equals("sichuan.basics.exchange")
                    || id.equals("sichuan.basics.win")
                    || id.equals("sichuan.basics.void")
                    || id.equals("sichuan.basics.battle")
                    || id.equals("basics.win")
                    || id.equals("basics.dora")
                    || id.equals("basics.furiten");
        }
    }

    public static final List<String> NORMAL_YAKU =
            List.of(
                    "riichi",
                    "tsumo",
                    "tanyao",
                    "yakuhai",
                    "pinfu",
                    "iipeikou",
                    "ippatsu",
                    "haitei",
                    "houtei",
                    "rinshan",
                    "chankan",
                    "double_riichi",
                    "chiitoitsu",
                    "toitoi",
                    "sanankou",
                    "sankantsu",
                    "sanshoku_doukou",
                    "shousangen",
                    "honroutou",
                    "sanshoku_doujun",
                    "ittsuu",
                    "chanta",
                    "ryanpeikou",
                    "junchan",
                    "honitsu",
                    "chinitsu");

    public static final List<String> YAKUMAN =
            List.of(
                    "kokushi",
                    "suuankou",
                    "daisangen",
                    "shousuushi",
                    "daisuushi",
                    "tsuuiisou",
                    "chinroutou",
                    "ryuuiisou",
                    "chuuren",
                    "suukantsu",
                    "tenhou",
                    "chiihou");

    private static final List<Entry> YAKU_ENTRIES =
            Stream.concat(
                            Stream.of(new Entry("yaku.read")),
                            Stream.concat(
                                            NORMAL_YAKU.stream(),
                                            Stream.concat(YAKUMAN.stream(), Stream.of("counted")))
                                    .map(id -> new Entry("yaku." + id)))
                    .toList();

    public static List<String> translationKeys() {
        return Stream.concat(
                        Arrays.stream(Page.values()).map(Page::titleKey),
                        Arrays.stream(Page.values())
                                .flatMap(p -> p.entries().stream())
                                .flatMap(e -> Stream.of(e.titleKey(), e.bodyKey())))
                .toList();
    }
}
