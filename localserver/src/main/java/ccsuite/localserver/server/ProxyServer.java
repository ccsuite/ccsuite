package ccsuite.localserver.server;

import com.sun.net.httpserver.*;
import org.slf4j.*;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Executors;

/**
 * Generic passthrough proxy to {@code api.anthropic.com}: forwards any path/method verbatim,
 * picking which account's OAuth bearer token to inject via {@link AccountRouter}'s normalized
 * pace ranking, retrying the next candidate on a retryable status. Modeled directly on the
 * {@code LocalWordProxy.java} reference proxy, substituting the token source and selection
 * policy — same header-stripping, retry, and per-chunk SSE-safe streaming shape.
 */
public final class ProxyServer {

    private static final Logger log = LoggerFactory.getLogger(ProxyServer.class);

    static final String API = "https://api.anthropic.com";
    static final String OAUTH_BETA = "oauth-2025-04-20";
    static final String DEFAULT_ANTHROPIC_VERSION = "2023-06-01";
    static final String USER_AGENT = "cclocalserver/1.0";

    static final Set<String> HOP = Set.of("host", "content-length", "connection", "authorization", "x-api-key",
            "accept-encoding", "transfer-encoding", "upgrade", "keep-alive", "te", "proxy-connection");
    static final Set<Integer> RETRY = Set.of(401, 403, 429, 500, 502, 503, 529);

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();

    private final AccountRouter router;
    private final int port;
    private HttpServer server;

    public ProxyServer(AccountRouter router, int port) {
        this.router = router; this.port = port;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        log.info("cclocalserver proxy: http://127.0.0.1:{} -> {}", port, API);
    }

    public void stop() { if (server != null) server.stop(0); }

    private void handle(HttpExchange ex) {
        try (ex) {
            var method = ex.getRequestMethod();
            var path = ex.getRequestURI().getRawPath();
            var body = ex.getRequestBody().readAllBytes();
            var candidates = router.rankedCandidates();
            if (candidates.isEmpty()) { respond(ex, 503, "no eligible account with headroom"); return; }

            for (var i = 0; i < candidates.size(); i++) {
                var id = candidates.get(i);
                var tokenOpt = router.tokenFor(id);
                if (tokenOpt.isEmpty()) continue;
                var req = build(method, API + ex.getRequestURI(), body, ex.getRequestHeaders(), tokenOpt.get());
                var r = HTTP.send(req, HttpResponse.BodyHandlers.ofInputStream());
                log.info("{} {} [{}] -> {}", method, path, id, r.statusCode());
                var last = i == candidates.size() - 1;
                if (RETRY.contains(r.statusCode()) && !last) {
                    if (r.statusCode() == 401) router.invalidateAfter401(id);
                    r.body().close();
                    continue;
                }
                router.markServed(id);
                stream(r, ex);
                return;
            }
        } catch (Exception e) {
            try { respond(ex, 502, "proxy error: " + e); } catch (Exception ignore) {}
        }
    }

    private static HttpRequest build(String method, String url, byte[] body, Headers in, String token) {
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

    private static void stream(HttpResponse<InputStream> r, HttpExchange ex) throws IOException {
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

    private static void respond(HttpExchange ex, int code, String msg) throws IOException {
        var b = msg.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        ex.sendResponseHeaders(code, b.length);
        try (var out = ex.getResponseBody()) { out.write(b); }
    }
}
