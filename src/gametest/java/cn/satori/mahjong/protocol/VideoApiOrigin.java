package cn.satori.mahjong.protocol;

import java.net.URI;

/** Filming JAR only. The normal origin validator still restricts this to loopback. */
public final class VideoApiOrigin {
    public static SatoriApi create(URI origin) {
        return new SatoriApi(origin);
    }
}
