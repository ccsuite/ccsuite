package ccsuite.oauthclient;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.*;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Thin client for the two Claude OAuth endpoints claude-swap relies on: the usage
 * snapshot (GET) and a refresh-token exchange (POST). Built on the JDK HttpClient +
 * Jackson — no extra deps, decoupled from the heavier PKCE {@code ccsuite-oauth}.
 */
public final class OAuthUsageClient {

    private static final Logger log = LoggerFactory.getLogger(OAuthUsageClient.class);

    static final String USAGE_URL = "https://api.anthropic.com/api/oauth/usage";
    static final String TOKEN_URL = "https://platform.claude.com/v1/oauth/token";
    static final String CLIENT_ID = "9d1c250a-e61b-44d9-88ed-5944d1962f5e";
    static final String BETA = "oauth-2025-04-20";
    static final String UA = "claude-swap/1.0";

    /** Spacing callers should leave between successive usage fetches so a multi-account
     *  sweep never bursts the endpoint into a rate-limit (mirrors usage_tracker's PRP-09 fix). */
    public static final long FETCH_SPACING_MS = 1200;
    private static final int MAX_RETRIES = 4;
    private static final long BASE_DELAY_MS = 800;
    private static final long MAX_DELAY_MS = 20_000;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper;

    public OAuthUsageClient(ObjectMapper mapper) { this.mapper = mapper; }

    /**
     * Fetch utilization for {@code accessToken}, retrying transient failures (HTTP 429, 5xx,
     * network/IO errors) with exponential backoff + full jitter. Non-retryable statuses
     * (401 and other 4xx) propagate immediately so the caller can refresh the token.
     */
    public AccountUsage fetchUsage(String accessToken) throws IOException, InterruptedException {
        IOException last = null;
        for (var attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                return fetchUsageOnce(accessToken);
            } catch (UsageHttpException e) {
                if (!retryable(e.status)) throw e;
                last = e;
                log.debug("usage HTTP {} (attempt {}/{}) — backing off", e.status, attempt + 1, MAX_RETRIES);
            } catch (IOException e) {
                last = e;
                log.debug("usage IO error (attempt {}/{}): {}", attempt + 1, MAX_RETRIES, e.toString());
            }
            if (attempt < MAX_RETRIES - 1) backoffSleep(attempt);
        }
        throw last != null ? last : new IOException("usage fetch failed");
    }

    private AccountUsage fetchUsageOnce(String accessToken) throws IOException, InterruptedException {
        var req = HttpRequest.newBuilder(URI.create(USAGE_URL))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + accessToken)
                .header("anthropic-beta", BETA)
                .header("User-Agent", UA)
                .GET().build();
        var resp = http.send(req, BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) throw new UsageHttpException(resp.statusCode(), resp.body());
        return parseUsage(mapper.readTree(resp.body()));
    }

    private static boolean retryable(int status) { return status == 429 || status >= 500; }

    static final String PROFILE_URL = "https://api.anthropic.com/api/oauth/profile";

    /**
     * The e-mail of the account an access token really belongs to, as Anthropic answers it (lower case), or
     * {@code null} when it cannot be told (expired token, no network, odd answer). One attempt, no retries: callers
     * treat {@code null} as "unknown", never as "not this account".
     */
    public String fetchProfileEmail(String accessToken) {
        try {
            var req = HttpRequest.newBuilder(URI.create(PROFILE_URL))
                    .timeout(Duration.ofSeconds(8))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("anthropic-beta", BETA)
                    .header("User-Agent", UA)
                    .GET().build();
            var resp = http.send(req, BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                log.debug("profile lookup: HTTP {}", resp.statusCode());
                return null;
            }
            return profileEmail(mapper.readTree(resp.body()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.debug("profile lookup failed: {}", e.toString());
            return null;
        }
    }

    static String profileEmail(JsonNode profile) {
        var account = profile.path("account");
        var email = account.hasNonNull("email") ? account.get("email") : account.get("email_address");
        return (email == null || email.isNull() || email.asText().isBlank()) ? null : email.asText().toLowerCase();
    }

    /** Exponential backoff with full jitter: sleep in [d/2, d], d = min(cap, base·2^attempt). */
    private static void backoffSleep(int attempt) throws InterruptedException {
        var exp = BASE_DELAY_MS << Math.min(attempt, 16);
        var capped = Math.max(1, Math.min(MAX_DELAY_MS, exp));
        Thread.sleep(capped / 2 + ThreadLocalRandom.current().nextLong(capped / 2 + 1));
    }

    static final long SEVEN_DAY_SECONDS = 7L * 24 * 60 * 60;

    static AccountUsage parseUsage(JsonNode data) {
        var h5 = data.get("five_hour");
        var d7 = data.get("seven_day");
        return new AccountUsage(pct(h5), resetsAt(h5), pct(d7), resetsAt(d7),
                elapsedPct(resetsAt(d7), SEVEN_DAY_SECONDS));
    }

    /** Fraction (0..100) of a fixed window already elapsed, derived from its reset instant. */
    static Double elapsedPct(String resetsAtIso, long windowSeconds) {
        if (resetsAtIso == null) return null;
        try {
            var until = java.time.Duration.between(
                    java.time.OffsetDateTime.now(), java.time.OffsetDateTime.parse(resetsAtIso)).getSeconds();
            return (windowSeconds - until) * 100.0 / windowSeconds;
        } catch (Exception e) {
            log.debug("could not parse reset timestamp '{}': {}", resetsAtIso, e.toString());
            return null;
        }
    }

    private static Double pct(JsonNode w) {
        if (w == null || w.isNull()) return null;
        var u = w.get("utilization");
        return (u == null || u.isNull()) ? null : u.asDouble();
    }

    private static String resetsAt(JsonNode w) {
        if (w == null || w.isNull()) return null;
        var r = w.get("resets_at");
        return (r == null || r.isNull()) ? null : r.asText();
    }

    /**
     * Exchange the refresh token in {@code credentials} (shape {@code {claudeAiOauth:{...}}})
     * for a fresh access token, returning an updated copy of the credentials tree. Returns
     * {@code null} when there is no refresh token or the exchange fails (logged at debug).
     */
    public JsonNode refresh(JsonNode credentials) {
        try {
            var oauth = credentials == null ? null : credentials.get("claudeAiOauth");
            var refreshTok = oauth == null ? null : oauth.get("refreshToken");
            if (refreshTok == null || refreshTok.isNull() || refreshTok.asText().isBlank()) return null;

            var body = mapper.createObjectNode();
            body.put("grant_type", "refresh_token");
            body.put("refresh_token", refreshTok.asText());
            body.put("client_id", CLIENT_ID);

            var req = HttpRequest.newBuilder(URI.create(TOKEN_URL))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("User-Agent", UA)
                    .POST(BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
            var resp = http.send(req, BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                log.debug("OAuth refresh failed: HTTP {} {}", resp.statusCode(), resp.body());
                return null;
            }
            return applyRefresh(credentials.deepCopy(), mapper.readTree(resp.body()));
        } catch (Exception e) {
            log.debug("OAuth refresh failed: {}", e.toString());
            return null;
        }
    }

    private JsonNode applyRefresh(JsonNode credentials, JsonNode r) {
        var oauth = (ObjectNode) credentials.get("claudeAiOauth");
        oauth.put("accessToken", r.get("access_token").asText());
        oauth.put("expiresAt", System.currentTimeMillis() + r.path("expires_in").asLong(0) * 1000);
        if (r.hasNonNull("refresh_token")) oauth.put("refreshToken", r.get("refresh_token").asText());
        if (r.hasNonNull("scope")) {
            var arr = mapper.createArrayNode();
            for (var s : r.get("scope").asText().split("\\s+")) if (!s.isBlank()) arr.add(s);
            oauth.set("scopes", arr);
        }
        return credentials;
    }
}
