package cn.satori.mahjong.client;

import static cn.satori.mahjong.client.HandInteractionFixture.require;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

final class WebsiteInteractionChecks {
    static void openAndReturn(String translation, String expectedUrl) {
        var mc = Minecraft.getInstance();
        var parent = mc.screen;
        String label = Component.translatable(translation).getString();
        var button =
                parent.children().stream()
                        .filter(Button.class::isInstance)
                        .map(Button.class::cast)
                        .filter(b -> b.getMessage().getString().equals(label))
                        .findFirst()
                        .orElseThrow();
        CameraInteractionChecks.click(button, .5);
        require(
                mc.screen instanceof ConfirmLinkScreen,
                "Website button did not open native browser confirmation");
        // Use the native Copy Link button to verify the actual URI, without launching a browser.
        String copy = CommonComponents.GUI_COPY_TO_CLIPBOARD.getString();
        var copyButton =
                mc.screen.children().stream()
                        .filter(Button.class::isInstance)
                        .map(Button.class::cast)
                        .filter(b -> b.getMessage().getString().equals(copy))
                        .findFirst()
                        .orElseThrow();
        CameraInteractionChecks.click(copyButton, .5);
        require(
                mc.keyboardHandler.getClipboard().equals(expectedUrl),
                "Website URL mismatch or credentials leaked");
        require(mc.screen == parent, "Website dialog did not return to its parent");
    }
}
