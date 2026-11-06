package ccsuite.localserver.server;

import ccsuite.localserver.lanes.Lane;
import ccsuite.localserver.lanes.Result;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.OptionalDouble;
import java.util.Set;

/**
 * The PAID lane: a passthrough to {@code api.anthropic.com} that injects the OAuth bearer token of an account picked by {@link AccountRouter}'s
 * normalized pace ranking, retrying the next candidate on a retryable status. This is the body of the old {@link ProxyServer}, unchanged in what it does,
 * moved behind {@link Lane} so a port can choose between it and the free lane. Modeled on the LocalWordProxy reference proxy: same header
 * stripping, same per-chunk SSE-safe streaming.
 */
public final class PaidLane implements Lane {

    private static final Logger log = LoggerFactory.getLogger(PaidLane.class);

    static final String API = "https://api.anthropic.com";
    static final String OAUTH_BETA = "oauth-2025-04-20";
    static final String DEFAULT_ANTHROPIC_VERSION = "2023-06-01";
    static final String USER_AGENT = "cclocalserver/1.0";

    static final Set<String> HOP = Set.of("host", "content-length", "connection", "authorization", "x-api-key",
            "accept-encoding", "transfer-encoding", "upgrade", "keep-alive", "te", "proxy-connection");
    static final Set<Integer> RETRY = Set.of(401, 403, 429, 500, 502, 503, 529);

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();

    final AccountRouter router;
    /** The one account this lane may use, or null/blank for the usual ranking. Read on every request, so it changes at once. */
    volatile java.util.function.Supplier<String> pin = () -> null;

    public PaidLane(AccountRouter router) { this.router = router; }

    public PaidLane pin(java.util.function.Supplier<String> pin) { this.pin = pin; return this; }

    /**
     * The accounts to try, best first: the ranking, or with a pin ONLY the pinned account, and only if it has headroom and can serve (otherwise nothing: the request
     * is refused, never moved to another account, so a measured run on one account is either on it or visibly not).
     */
    static List<String> candidates(List<String> ranked, String pinned) {
        if (pinned == null || pinned.isBlank()) return ranked;
        return ranked.contains(pinned) ? List.of(pinned) : List.of();
    }

    @Override public String name() { return "paid"; }

    @Override public boolean available() { return router != null; }

    @Override public boolean hasHeadroom() { return !router.rankedCandidates().isEmpty(); }

    @Override public OptionalDouble weeklyPace() { return router.weeklyPace(); }

    @Override public Result send(HttpExchange ex, byte[] body) throws Exception {
        var method = ex.getRequestMethod();
        var path = ex.getRequestURI().getRawPath();
        var pinned = pin.get();
        var isPinned = pinned != null && !pinned.isBlank();
        var candidates = candidates(router.rankedCandidates(), pinned);
        if (candidates.isEmpty()) return fail(ex, 503, isPinned ? "the pinned paid account " + pinned + " has no headroom or cannot serve; not falling back to another account" : "no eligible account with headroom");
        var lastStatus = 0;
        var lastWhy = "no account could be tried";
        for (var i = 0; i < candidates.size(); i++) {
            var id = candidates.get(i);
            var tokenOpt = router.tokenFor(id);
            if (tokenOpt.isEmpty()) continue;
            HttpResponse<InputStream> r;
            try {
                r = HTTP.send(build(method, API + ex.getRequestURI(), body, ex.getRequestHeaders(), tokenOpt.get()), HttpResponse.BodyHandlers.ofInputStream());
            } catch (IOException e) {
                lastStatus = 502; lastWhy = "proxy error: " + e;
                continue;
            }
            log.info("{} {} [{}] -> {}", method, path, id, r.statusCode());
            var last = i == candidates.size() - 1;
            if (RETRY.contains(r.statusCode()) && !last) {
                if (r.statusCode() == 401) router.invalidateAfter401(id);
                lastStatus = r.statusCode(); lastWhy = "account " + id + " answered " + r.statusCode();
                r.body().close();
                continue;
            }
            router.markServed(id);
            stream(r, ex);
            return Result.done(r.statusCode(), id);
        }
        return fail(ex, lastStatus == 0 ? 502 : lastStatus, lastWhy);
    }

    /** The client gets the failure now, as it is: there is no other lane or account to move to. */
    static Result fail(HttpExchange ex, int status, String message) throws IOException {
        respond(ex, status, message);
        return Result.done(status);
    }

    static HttpRequest build(String method, String url, byte[] body, Headers in, String token) {
        var b = HttpRequest.newBuilder(URI.create(url));
        in.forEach((k, vs) -> { if (!HOP.contains(k.toLowerCase()) && !vs.isEmpty()) b.header(k, vs.get(0)); });
        b.header("authorization", "Bearer " + token);
        b.header("anthropic-beta", OAUTH_BETA);
        b.header("user-agent", USER_AGENT);
        b.header("accept-encoding", "identity");
        if (in.keySet().stream().noneMatch(k -> k.equalsIgnoreCase("anthropic-version")))
            b.header("anthropic-version", DEFAULT_ANTHROPIC_VERSION);
        var pub = (method.equals("GET") || method.equals("HEAD"))
                ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body);
        return b.method(method, pub).build();
    }

    static void stream(HttpResponse<InputStream> r, HttpExchange ex) throws IOException {
        r.headers().map().forEach((k, vs) -> {
            var lk = k.toLowerCase();
            if (!lk.equals("content-length") && !lk.equals("transfer-encoding") && !lk.equals("content-encoding")
                    && !lk.equals("connection") && !lk.startsWith(":"))
                vs.forEach(v -> ex.getResponseHeaders().add(k, v));
        });
        ex.sendResponseHeaders(r.statusCode(), 0);
        try (var isr = r.body(); var out = ex.getResponseBody()) {
            var buf = new byte[8192];
            int n;
            while ((n = isr.read(buf)) != -1) { out.write(buf, 0, n); out.flush(); } // flush per-chunk = live SSE
        }
    }

    static void respond(HttpExchange ex, int code, String msg) throws IOException {
        var b = msg.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(code, b.length);
        try (var out = ex.getResponseBody()) { out.write(b); }
    }
}
