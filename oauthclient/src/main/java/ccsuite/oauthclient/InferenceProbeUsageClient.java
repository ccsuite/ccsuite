package ccsuite.oauthclient;

import org.slf4j.*;

import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.time.Instant;

/**
 * Fallback usage source for when {@link OAuthUsageClient}'s {@code /api/oauth/usage} GET is
 * unavailable (it 429s aggressively for some plans — see anthropics/claude-code#31021/#31637).
 * Fires a minimal (1 output token) Haiku completion against the real Messages API and reads
 * utilization off the {@code anthropic-ratelimit-unified-*} response headers Anthropic attaches
 * to every inference call — no dedicated usage endpoint involved at all. Confirmed live against
 * {@code api.anthropic.com/v1/messages} on 2026-07-17: a 1-token completion returns
 * {@code anthropic-ratelimit-unified-5h-utilization} / {@code -7d-utilization} as 0..1 fractions
 * plus matching {@code -reset} unix-second timestamps.
 *
 * <p>Unlike the free GET in {@link OAuthUsageClient}, each call here costs a few real tokens
 * (~8 input + 1 output). Callers must treat this as a manually-triggered, on-demand fallback —
 * never poll it on a timer.
 */
public final class InferenceProbeUsageClient {

    private static final Logger log = LoggerFactory.getLogger(InferenceProbeUsageClient.class);

    static final String MESSAGES_URL = "https://api.anthropic.com/v1/messages";
    static final String PROBE_MODEL = "claude-haiku-4-5-20251001";
    static final String BETA = "oauth-2025-04-20";
    static final String UA = "claude-swap/1.0";
    private static final String PROBE_BODY =
            "{\"model\":\"" + PROBE_MODEL + "\",\"max_tokens\":1,\"messages\":[{\"role\":\"user\",\"content\":\".\"}]}";

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    /**
     * Fire the probe once. No retry: retrying here would multiply the token cost every attempt,
     * so backoff is the caller's decision, not this client's.
     */
    public AccountUsage fetchUsage(String accessToken) throws IOException, InterruptedException {
        var req = HttpRequest.newBuilder(URI.create(MESSAGES_URL))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + accessToken)
                .header("anthropic-version", "2023-06-01")
                .header("anthropic-beta", BETA)
                .header("Content-Type", "application/json")
                .header("User-Agent", UA)
                .POST(BodyPublishers.ofString(PROBE_BODY)).build();
        var resp = http.send(req, BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) throw new UsageHttpException(resp.statusCode(), resp.body());

        var h5 = utilizationPct(resp, "anthropic-ratelimit-unified-5h-utilization");
        var h5Reset = resetIso(resp, "anthropic-ratelimit-unified-5h-reset");
        var d7 = utilizationPct(resp, "anthropic-ratelimit-unified-7d-utilization");
        var d7Reset = resetIso(resp, "anthropic-ratelimit-unified-7d-reset");
        log.debug("probe usage: 5h={} ({}) 7d={} ({})", h5, h5Reset, d7, d7Reset);
        return new AccountUsage(h5, h5Reset, d7, d7Reset,
                OAuthUsageClient.elapsedPct(d7Reset, OAuthUsageClient.SEVEN_DAY_SECONDS));
    }

    /** Header carries a 0..1 fraction; the rest of the codebase works in 0..100 percentages. */
    private static Double utilizationPct(HttpResponse<String> resp, String header) {
        return resp.headers().firstValue(header).map(v -> {
            try { return Double.parseDouble(v) * 100.0; } catch (NumberFormatException e) { return null; }
        }).orElse(null);
    }

    /** Header carries a unix-second timestamp; convert to the ISO-8601 string the rest of the
     *  codebase expects (parseable by {@code OffsetDateTime.parse}). */
    private static String resetIso(HttpResponse<String> resp, String header) {
        return resp.headers().firstValue(header).map(v -> {
            try { return Instant.ofEpochSecond(Long.parseLong(v)).toString(); }
            catch (NumberFormatException e) { return null; }
        }).orElse(null);
    }
}
