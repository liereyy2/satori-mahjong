package cn.satori.mahjong.client;

import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.Screen;

/** Public browser entry points never carry the mod's password, session or API key. */
final class SatoriWebsite {
    static final String LOGIN = "https://mj.satori-ai.cn/login";
    static final String PROFILE = "https://mj.satori-ai.cn/profile";
    static final String CREDITS = PROFILE + "?view=credits";
    static final String MESSAGES = "https://mj.satori-ai.cn/messages";

    static void openMessages(Screen parent) {
        ConfirmLinkScreen.confirmLinkNow(parent, MESSAGES);
    }

    static void openLogin(Screen parent) {
        ConfirmLinkScreen.confirmLinkNow(parent, LOGIN);
    }

    static void openProfile(Screen parent) {
        ConfirmLinkScreen.confirmLinkNow(parent, PROFILE);
    }

    static void openCredits(Screen parent) {
        ConfirmLinkScreen.confirmLinkNow(parent, CREDITS);
    }
}
