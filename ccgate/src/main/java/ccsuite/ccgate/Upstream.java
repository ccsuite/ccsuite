package ccsuite.ccgate;

import module java.base;
import module java.net.http;
import com.sun.net.httpserver.Headers;

/** The request to api.anthropic.com for one attempt: the caller's headers minus its credentials, plus the account's token. */
public final class Upstream {

    static final String API = "https://api.anthropic.com", OAUTH_BETA = "oauth-2025-04-20", VERSION = "2023-06-01";
    static final Set<String> DROP = Set.of("host", "content-length", "connection", "authorization", "x-api-key", "accept-encoding",
            "transfer-encoding", "upgrade", "keep-alive", "te", "proxy-connection", "anthropic-beta", "user-agent");
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();

    public static HttpResponse<InputStream> send(String method, String pathAndQuery, byte[] body, Headers in, String token)
            throws IOException, InterruptedException {
        var b = HttpRequest.newBuilder(URI.create(API + pathAndQuery));
        in.forEach((k, vs) -> {
            var lk = k.toLowerCase(Locale.ROOT);
            if (!DROP.contains(lk) && !lk.startsWith("x-ccgate-") && !vs.isEmpty()) b.header(k, vs.get(0));
        });
        b.header("authorization", "Bearer " + token);
        b.header("anthropic-beta", beta(in.getFirst("anthropic-beta")));
        b.header("user-agent", ua(in.getFirst("user-agent")));
        b.header("accept-encoding", "identity");
        if (in.getFirst("anthropic-version") == null) b.header("anthropic-version", VERSION);
        var pub = method.equals("GET") || method.equals("HEAD") ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body);
        return HTTP.send(b.method(method, pub).build(), HttpResponse.BodyHandlers.ofInputStream());
    }

    /** The caller's beta list with the OAuth beta in it once (ccls appended a second header instead). */
    static String beta(String caller) {
        if (caller == null || caller.isBlank()) return OAUTH_BETA;
        return caller.contains(OAUTH_BETA) ? caller : caller + "," + OAUTH_BETA;
    }

    static String ua(String caller) {
        return caller == null || caller.isBlank() ? "ccgate/1.0" : caller;
    }

    private Upstream() { }
}
