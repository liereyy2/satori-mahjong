package cn.satori.mahjong.protocol;

import java.util.ArrayList;
import java.util.List;

/** Authorized center-panel presentation, using MahjongBoard3D's 448px reference layout. */
public record TableCenter(
        int viewer,
        String ruleMode,
        String openingPhase,
        int matchRound,
        int roundWind,
        int round,
        int remaining,
        int honba,
        int kyotaku,
        List<Seat> seats) {
    public static final float UNIT = TileGeometry.CENTER_HALF * 2 / 448;
    public static final float INNER_HALF = Math.round(448f / 3.72f) * UNIT;
    public static final float SCORE_RADIUS = INNER_HALF + 34 * UNIT;
    public static final float STICK_RADIUS = SCORE_RADIUS + 46 * UNIT;

    public record Seat(
            int score,
            int wind,
            boolean dealer,
            boolean riichi,
            boolean active,
            int voidSuit,
            boolean won) {}

    public int activeSeat() {
        for (int seat = 0; seat < seats.size(); seat++) if (seats.get(seat).active()) return seat;
        return -1;
    }

    public TableCenter withOpeningPhase(String phase) {
        return new TableCenter(
                viewer,
                ruleMode,
                phase,
                matchRound,
                roundWind,
                round,
                remaining,
                honba,
                kyotaku,
                seats);
    }

    public TableCenter withActiveSeat(int active) {
        var displayed = new ArrayList<Seat>();
        for (int seat = 0; seat < seats.size(); seat++) {
            var info = seats.get(seat);
            displayed.add(
                    new Seat(
                            info.score(),
                            info.wind(),
                            info.dealer(),
                            info.riichi(),
                            seat == active,
                            info.voidSuit(),
                            info.won()));
        }
        return new TableCenter(
                viewer,
                ruleMode,
                openingPhase,
                matchRound,
                roundWind,
                round,
                remaining,
                honba,
                kyotaku,
                List.copyOf(displayed));
    }

    public static TableCenter from(CloudView view) {
        var p = view.publicData();
        int dealer = p.get("oya").getAsInt();
        int active = view.decisionSeat();
        var seats = new ArrayList<Seat>();
        for (int seat = 0; seat < 4; seat++)
            seats.add(
                    new Seat(
                            p.getAsJsonArray("scores").get(seat).getAsInt(),
                            Math.floorMod(seat - dealer, 4),
                            seat == dealer,
                            p.getAsJsonArray("riichi_accepted").get(seat).getAsBoolean(),
                            seat == active && view.status().equals("playing"),
                            view.ruleMode().equals("sichuan")
                                            && !p.getAsJsonObject("sichuan")
                                                    .getAsJsonArray("void_suits")
                                                    .get(seat)
                                                    .isJsonNull()
                                    ? p.getAsJsonObject("sichuan")
                                            .getAsJsonArray("void_suits")
                                            .get(seat)
                                            .getAsInt()
                                    : -1,
                            view.ruleMode().equals("sichuan")
                                    && p.getAsJsonObject("sichuan")
                                            .getAsJsonArray("won")
                                            .get(seat)
                                            .getAsBoolean()));
        return new TableCenter(
                view.viewerSeat,
                view.ruleMode(),
                view.ruleMode().equals("sichuan")
                        ? p.getAsJsonObject("sichuan").get("opening_phase").getAsString()
                        : "",
                view.ruleMode().equals("sichuan")
                        ? p.getAsJsonObject("sichuan").get("round").getAsInt()
                        : 0,
                p.get("bakaze").getAsInt() - 27,
                p.get("kyoku").getAsInt(),
                p.get("tiles_left").getAsInt(),
                p.get("honba").getAsInt(),
                p.get("kyotaku").getAsInt(),
                List.copyOf(seats));
    }
}
