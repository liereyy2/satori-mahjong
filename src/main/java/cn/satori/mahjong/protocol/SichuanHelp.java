package cn.satori.mahjong.protocol;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/** Read-only guide to the Sichuan blood-battle rules implemented by the cloud engine. */
public final class SichuanHelp {
    private SichuanHelp() {}

    public static List<RiichiHelp.Entry> entries(RiichiHelp.Page page) {
        String[] ids =
                switch (page) {
                    case BASICS ->
                            new String[] {
                                "basics.win",
                                "basics.exchange",
                                "basics.void",
                                "basics.battle",
                                "basics.shape",
                                "basics.scope",
                                "basics.example"
                            };
                    case PLAY ->
                            new String[] {
                                "play.tiles",
                                "play.exchange",
                                "play.void",
                                "play.turn",
                                "play.calls",
                                "play.kans",
                                "play.pass",
                                "play.won"
                            };
                    case YAKU ->
                            new String[] {
                                "yaku.read",
                                "yaku.pinghu",
                                "yaku.duidui",
                                "yaku.qingyise",
                                "yaku.qidui",
                                "yaku.longqidui",
                                "yaku.quandaiyao",
                                "yaku.jiangdui",
                                "yaku.jingou",
                                "yaku.gen",
                                "yaku.zimo",
                                "yaku.rinshan",
                                "yaku.chankan",
                                "yaku.haidi",
                                "yaku.tiandi"
                            };
                    case RIICHI ->
                            new String[] {
                                "battle.choose",
                                "battle.restrict",
                                "battle.priority",
                                "battle.continue",
                                "battle.end"
                            };
                    case SCORING ->
                            new String[] {
                                "scoring.units",
                                "scoring.payments",
                                "scoring.kan",
                                "scoring.tax",
                                "scoring.pig",
                                "scoring.ready",
                                "scoring.match"
                            };
                };
        return Arrays.stream(ids).map(id -> new RiichiHelp.Entry("sichuan." + id)).toList();
    }

    public static String titleKey(RiichiHelp.Page page) {
        return "satori.help.sichuan.tab." + page.id;
    }

    public static List<String> translationKeys() {
        return Arrays.stream(RiichiHelp.Page.values())
                .flatMap(
                        page ->
                                Stream.concat(
                                        Stream.of(titleKey(page)),
                                        entries(page).stream()
                                                .flatMap(
                                                        e -> Stream.of(e.titleKey(), e.bodyKey()))))
                .toList();
    }
}
