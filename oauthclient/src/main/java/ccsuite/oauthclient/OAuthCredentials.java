package ccsuite.oauthclient;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Small shared reads over a raw {@code {claudeAiOauth:{...}}} credentials tree — used
 * wherever a token needs pulling out or a staleness check made, without going through
 * {@link UsageService}'s fetch-usage flow (e.g. a proxy that forwards arbitrary requests).
 */
public final class OAuthCredentials {

    private static final long EXPIRY_BUFFER_MS = 5 * 60 * 1000;

    private OAuthCredentials() {}

    public static boolean expired(JsonNode creds) {
        var oauth = creds == null ? null : creds.get("claudeAiOauth");
        var exp = oauth == null ? null : oauth.get("expiresAt");
        return exp != null && exp.isNumber() && System.currentTimeMillis() + EXPIRY_BUFFER_MS >= exp.asLong();
    }

    public static String accessToken(JsonNode creds) {
        var oauth = creds == null ? null : creds.get("claudeAiOauth");
        var t = oauth == null ? null : oauth.get("accessToken");
        return (t == null || t.isNull() || t.asText().isBlank()) ? null : t.asText();
    }
}
