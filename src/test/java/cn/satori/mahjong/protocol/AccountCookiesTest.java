package cn.satori.mahjong.protocol;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.net.*;
import java.util.*;

class AccountCookiesTest {
    private static final URI LOGIN = SatoriApi.PRODUCTION.resolve("/api/account/login");
    private static final URI SESSION = SatoriApi.PRODUCTION.resolve("/api/account/session");
    private static final String SESSION_COOKIE =
            "__Secure-satori_session_id=test-session; Domain=satori-ai.cn; HttpOnly;"
                + " Max-Age=604800; Path=/; SameSite=lax; Secure";

    @Test
    void productionParentDomainSessionSurvivesLoginAndIsSentInBrowserFormat() throws Exception {
        CookieManager cookies = AccountCookies.create(SatoriApi.PRODUCTION);
        cookies.put(
                SESSION,
                Map.of(
                        "Set-Cookie",
                        List.of(
                                "preauth=nonce; Path=/; Secure; HttpOnly; Max-Age=3600;"
                                    + " SameSite=lax")));
        assertEquals(List.of("preauth=nonce"), cookies.get(LOGIN, Map.of()).get("Cookie"));
        cookies.put(
                LOGIN,
                Map.of(
                        "Set-Cookie",
                        List.of(SESSION_COOKIE, "preauth=; Path=/; Secure; Max-Age=0")));
        assertEquals(
                List.of("__Secure-satori_session_id=test-session"),
                cookies.get(SESSION, Map.of()).get("Cookie"));
        assertEquals(
                List.of("__Secure-satori_session_id=test-session"),
                cookies.get(SatoriApi.PRODUCTION.resolve("/api/game/v1/games"), Map.of())
                        .get("Cookie"));
        cookies.put(
                LOGIN,
                Map.of(
                        "Set-Cookie",
                        List.of(
                                "__Secure-satori_session_id=; Domain=satori-ai.cn; Path=/;"
                                    + " Max-Age=0; Secure")));
        assertTrue(cookies.getCookieStore().getCookies().isEmpty());
    }

    @Test
    void secureAndPathRestrictionsRemainEnforced() throws Exception {
        CookieManager cookies = AccountCookies.create(SatoriApi.PRODUCTION);
        cookies.put(
                LOGIN,
                Map.of(
                        "Set-Cookie",
                        List.of("scoped=value; Path=/api/account; Secure; Max-Age=3600")));
        assertEquals(List.of("scoped=value"), cookies.get(SESSION, Map.of()).get("Cookie"));
        for (String target :
                List.of(
                        "http://mj.satori-ai.cn/api/account/session",
                        "https://unrelated.example/api/account/session",
                        "https://mj.satori-ai.cn/api/game/v1/games"))
            assertTrue(cookies.get(URI.create(target), Map.of()).get("Cookie").isEmpty());
    }

    @Test
    void rejectsForeignSourcesDomainsAndInsecureSecurePrefix() throws Exception {
        CookieManager cookies = AccountCookies.create(SatoriApi.PRODUCTION);
        for (String domain :
                List.of("cn", "evil.example", "satori-ai.cn.evil.example", "not-satori-ai.cn"))
            cookies.put(
                    LOGIN,
                    Map.of(
                            "Set-Cookie",
                            List.of("invalid=value; Domain=" + domain + "; Path=/; Secure")));
        cookies.put(
                URI.create("https://unrelated.example/login"),
                Map.of("Set-Cookie", List.of(SESSION_COOKIE)));
        cookies.put(
                URI.create("http://mj.satori-ai.cn/login"),
                Map.of("Set-Cookie", List.of(SESSION_COOKIE)));
        cookies.put(
                LOGIN,
                Map.of(
                        "Set-Cookie",
                        List.of("__Secure-invalid=value; Domain=satori-ai.cn; Path=/")));
        assertTrue(cookies.getCookieStore().getCookies().isEmpty());
    }
}
