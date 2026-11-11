package cn.satori.mahjong.client;

import cn.satori.mahjong.protocol.*;

import com.google.gson.JsonArray;

import java.io.IOException;
import java.util.*;

/** One local Satori identity; no credentials or account cache belong to a world table. */
final class ClientIdentity {
    SatoriApi api;
    final AccountConfig config;
    final Set<ClientController> controllers = new LinkedHashSet<>();
    ClientController focused;
    boolean restoreAttempted, checkingSession, authenticating;
    long nextSessionCheck;
    int generation;
    JsonArray friends = new JsonArray();
    List<AccountKey> keys = List.of();
    UUID selectedKey;
    boolean keysLoading;
    String keysMessage = "keys.loading";
    int keysRevision, keysRequest;

    ClientIdentity(SatoriApi api, AccountConfig config) {
        this.api = api;
        this.config = config;
    }

    void clear(boolean forget, String status) {
        generation++;
        restoreAttempted = forget;
        checkingSession = false;
        authenticating = false;
        friends = new JsonArray();
        keysRequest++;
        keys = List.of();
        selectedKey = null;
        keysLoading = false;
        keysRevision++;
        if (forget) {
            try {
                config.clear();
            } catch (IOException error) {
                status = "config_clear_error";
            }
        }
        for (var controller : controllers) controller.resetLocal(status);
    }
}
