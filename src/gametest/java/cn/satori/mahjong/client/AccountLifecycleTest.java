package cn.satori.mahjong.client;

import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

final class AccountLifecycleTest {
    static void run(ClientGameTestContext context) {
        var checks = context.computeOnClient(mc -> new AccountLifecycleChecks());
        try {
            for (var step : checks.steps()) {
                context.waitFor(mc -> step.ready().getAsBoolean());
                context.runOnClient(mc -> step.action().run());
            }
        } finally {
            context.runOnClient(mc -> checks.close());
        }
    }
}
