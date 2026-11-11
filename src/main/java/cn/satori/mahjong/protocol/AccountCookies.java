package cn.satori.mahjong.protocol;

import java.net.CookieManager;
import java.net.URI;
import java.util.Locale;

/** Cookie acceptance for the fixed account origin and its shared Satori session domain. */
final class AccountCookies {
    private AccountCookies() {}

    static CookieManager create(URI origin) {
        return new CookieManager(
                null,
                (source, cookie) -> {
                    if (!origin.getScheme().equalsIgnoreCase(source.getScheme())
                            || !origin.getHost().equalsIgnoreCase(source.getHost())
                            || origin.getPort() != source.getPort()) return false;
                    String domain = cookie.getDomain();
                    if (domain == null) return false;
                    domain = domain.toLowerCase(Locale.ROOT);
                    if (domain.startsWith(".")) domain = domain.substring(1);
                    boolean ownHost = domain.equals(origin.getHost().toLowerCase(Locale.ROOT));
                    boolean sharedDomain =
                            origin.equals(SatoriApi.PRODUCTION) && domain.equals("satori-ai.cn");
                    if (!ownHost && !sharedDomain) return false;
                    boolean https = source.getScheme().equalsIgnoreCase("https");
                    if (cookie.getSecure() && !https) return false;
                    if (cookie.getName().startsWith("__Secure-") && (!https || !cookie.getSecure()))
                        return false;
                    // HTTP Set-Cookie uses name=value, without RFC 2965 $Version/$Domain
                    // attributes.
                    cookie.setVersion(0);
                    return true;
                });
    }
}
