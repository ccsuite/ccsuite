package ccsuite.localserver.lanes;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Small JSON answers in the Messages API's own shapes. */
final class Replies {

    static final ObjectMapper JSON = new ObjectMapper();

    static final String MODELS = "{\"data\":[{\"type\":\"model\",\"id\":\"claude-sonnet-5-5\",\"display_name\":\"Sonnet 5.5\",\"created_at\":\"2026-01-01T00:00:00Z\"},"
            + "{\"type\":\"model\",\"id\":\"claude-haiku-5-5\",\"display_name\":\"Haiku 5.5\",\"created_at\":\"2026-01-01T00:00:00Z\"}],\"has_more\":false,\"first_id\":\"claude-sonnet-5-5\",\"last_id\":\"claude-haiku-5-5\"}";

    static String modelOf(byte[] body) {
        try { return body.length == 0 ? null : JSON.readTree(body).path("model").asText(null); } catch (IOException e) { return null; }
    }

    static String error(int status, String message) {
        var type = status == 429 ? "rate_limit_error" : status == 400 ? "invalid_request_error" : status == 404 ? "not_found_error" : "api_error";
        return JSON.createObjectNode().put("type", "error").set("error", JSON.createObjectNode().put("type", type).put("message", message)).toString();
    }

    static void send(HttpExchange ex, int status, String json) throws IOException {
        var b = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("content-type", "application/json");
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
    }

    private Replies() {}
}
