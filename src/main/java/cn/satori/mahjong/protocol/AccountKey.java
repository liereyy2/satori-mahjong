package cn.satori.mahjong.protocol;

import java.util.UUID;

/** Display metadata only. Plaintext is fetched solely for an explicit AI submission. */
public record AccountKey(UUID id, String name, String masked, String status) {
    public boolean available() {
        return status.equals("active");
    }
}
