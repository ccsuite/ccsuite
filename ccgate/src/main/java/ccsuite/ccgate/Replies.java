package ccsuite.ccgate;

import module java.base;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;

/** Answers the gate writes itself, in the Messages API's error shape so every client shows the sentence. */
public final class Replies {

    static final ObjectMapper JSON = new ObjectMapper();

    public static void error(HttpExchange ex, int status, String message) throws IOException {
        var type = switch (status) {
            case 401 -> "authentication_error";
            case 403 -> "permission_error";
            case 429 -> "rate_limit_error";
            case 503 -> "overloaded_error";
            default -> "api_error";
        };
        var body = JSON.writeValueAsBytes(Map.of("type", "error", "error", Map.of("type", type, "message", "ccgate: " + message)));
        ex.getResponseHeaders().set("content-type", "application/json");
        ex.getResponseHeaders().set("x-ccgate-refused", "true");
        ex.sendResponseHeaders(status, body.length);
        try (var out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    /** Copies the upstream headers that make sense to pass back. */
    public static void copyHeaders(Map<String, List<String>> from, HttpExchange ex) {
        from.forEach((k, vs) -> {
            var lk = k.toLowerCase(Locale.ROOT);
            if (!Set.of("content-length", "transfer-encoding", "content-encoding", "connection").contains(lk) && !lk.startsWith(":"))
                vs.forEach(v -> ex.getResponseHeaders().add(k, v));
        });
    }

    private Replies() { }
}
