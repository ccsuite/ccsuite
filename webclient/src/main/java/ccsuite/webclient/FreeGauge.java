package ccsuite.webclient;

import module java.base;
import ccsuite.base.model.CCAPICredential;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;

/**
 * Reads the usage gauge of a free claude.ai account. The usage endpoint shows nothing for a free account; the only reading is the
 * {@code message_limit} event that comes with every streamed reply ({@code windows.5h.utilization}, {@code windows.7d.utilization},
 * as fractions in steps of 0.01). So the probe sends one tiny message in a temporary chat ("hi", one word back) and reads that event.
 */
public final class FreeGauge {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/152.0.0.0 Safari/537.36";

    /** Fractions of a window (0.98 = 98%); null when the reply carried no gauge. {@code model} is the model that answered. */
    public record Reading(Double fiveHour, Double sevenDay, String fiveStatus, Long fiveResetsAt, String model, long firstEventMillis, long totalMillis) {}

    /** One reply of a chat: the gauge it carried, the uuid of the assistant message (the parent of the next message in the same chat) and its text. */
    public record Turn(Reading reading, String uuid, String text) {}

    /** A new temporary chat (its uuid), to be continued with {@link #turn}. */
    public static String newChat(CCAPICredential c, String model) throws IOException {
        var http = WebHttp.client();
        var base = baseOf(c);
        var conversation = UUID.randomUUID().toString();
        var create = JSON.createObjectNode().put("uuid", conversation).put("name", "bench").put("is_temporary", true).put("include_conversation_preferences", true);
        if (model != null) create.put("model", model);
        try (var r = http.newCall(request(c, base, base + "/api/organizations/" + c.orgId() + "/chat_conversations", create.toString(), "*/*")).execute()) {
            if (!r.isSuccessful()) throw new IOException("create chat failed, HTTP " + r.code() + " " + body(r));
        }
        return conversation;
    }

    /** Sends one message in a chat. {@code parentUuid} is the previous reply's uuid; null for the first message. */
    public static Turn turn(CCAPICredential c, String conversation, String prompt, String model, String parentUuid) throws IOException {
        var base = baseOf(c);
        var message = JSON.createObjectNode().put("prompt", prompt).put("timezone", "UTC").put("rendering_mode", "messages");
        if (model != null) message.put("model", model);
        if (parentUuid != null) message.put("parent_message_uuid", parentUuid);
        var t0 = System.nanoTime();
        try (var r = WebHttp.client().newCall(request(c, base, base + "/api/organizations/" + c.orgId() + "/chat_conversations/" + conversation + "/completion", message.toString(), "text/event-stream")).execute()) {
            if (!r.isSuccessful()) {
                var text = r.body() == null ? "" : r.body().string();
                var spent = exceeded(r.code(), text);
                if (spent != null) return new Turn(spent, null, "");
                throw new IOException("HTTP " + r.code() + " " + text.substring(0, Math.min(200, text.length())));
            }
            return readTurn(r.body().byteStream(), t0);
        }
    }

    static String baseOf(CCAPICredential c) {
        var base = c.resolvedCcapiBaseUrl();
        return base.startsWith("http") ? base : "https://" + base;
    }

    public static Reading probe(CCAPICredential c, String model) throws IOException {
        var http = WebHttp.client();
        var base = c.resolvedCcapiBaseUrl();
        base = base.startsWith("http") ? base : "https://" + base;
        var org = base + "/api/organizations/" + c.orgId();
        var conversation = UUID.randomUUID().toString();
        var create = JSON.createObjectNode().put("uuid", conversation).put("name", "gauge").put("is_temporary", true).put("include_conversation_preferences", true);
        if (model != null) create.put("model", model);
        try (var r = http.newCall(request(c, base, org + "/chat_conversations", create.toString(), "*/*")).execute()) {
            if (!r.isSuccessful()) throw new IOException("gauge: create chat failed, HTTP " + r.code() + " " + body(r));
        }
        var message = JSON.createObjectNode().put("prompt", "Reply with the single word: ok").put("timezone", "UTC").put("rendering_mode", "messages");
        if (model != null) message.put("model", model);
        var t0 = System.nanoTime();
        try (var r = http.newCall(request(c, base, org + "/chat_conversations/" + conversation + "/completion", message.toString(), "text/event-stream")).execute()) {
            if (!r.isSuccessful()) {
                var text = r.body() == null ? "" : r.body().string();
                var spent = exceeded(r.code(), text);
                if (spent != null) return spent;
                throw new IOException("gauge: HTTP " + r.code() + " " + text.substring(0, Math.min(200, text.length())));
            }
            return read(r.body().byteStream(), t0);
        }
    }

    /**
     * An account whose window is used up answers HTTP 429 with {@code exceeded_limit} instead of a reply, and the same JSON says which window
     * ({@code representativeClaim}: five_hour or seven_day) and when it resets ({@code resetsAt}, epoch seconds). That is a reading, not a failure:
     * the used-up window counts as 100% and its status is {@code exceeded_limit}. Null when the body is anything else.
     */
    public static Reading exceeded(int code, String body) {
        if (code != 429) return null;
        try {
            var outer = JSON.readTree(body);
            var message = outer.path("error").path("message").asText("");
            var inner = message.startsWith("{") ? JSON.readTree(message) : outer;
            if (!"exceeded_limit".equals(inner.path("type").asText())) return null;
            var week = inner.path("representativeClaim").asText("").contains("seven");
            Long resets = inner.hasNonNull("resetsAt") ? inner.get("resetsAt").asLong() : null;
            return new Reading(week ? null : 1.0, week ? 1.0 : null, "exceeded_limit", resets, null, 0, 0);
        } catch (IOException e) {
            return null;
        }
    }

    static Reading read(InputStream in, long t0) throws IOException {
        return readTurn(in, t0).reading();
    }

    static Turn readTurn(InputStream in, long t0) throws IOException {
        Double five = null, seven = null;
        String status = null, model = null, uuid = null;
        var text = new StringBuilder();
        Long resets = null;
        var first = -1L;
        try (var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            for (var line = reader.readLine(); line != null; line = reader.readLine()) {
                if (System.getenv("GAUGE_DEBUG") != null) System.err.println("RAW> " + (line));
                if (!line.startsWith("data:")) continue;
                if (first < 0) first = (System.nanoTime() - t0) / 1_000_000;
                JsonNode event;
                try { event = JSON.readTree(line.substring(5).strip()); } catch (IOException e) { continue; }
                if (event.path("type").asText().equals("message_start")) { model = event.path("message").path("model").asText(null); uuid = event.path("message").path("uuid").asText(null); }
                if (event.path("type").asText().equals("content_block_delta")) text.append(event.path("delta").path("text").asText(""));
                var windows = event.path("message_limit").path("windows");
                if (event.path("type").asText().equals("message_limit") || windows.isObject()) {
                    if (!windows.isObject()) windows = event.path("windows");
                    five = windows.path("5h").hasNonNull("utilization") ? windows.path("5h").get("utilization").asDouble() : null;
                    seven = windows.path("7d").hasNonNull("utilization") ? windows.path("7d").get("utilization").asDouble() : null;
                    status = windows.path("5h").path("status").asText(null);
                    resets = windows.path("5h").hasNonNull("resets_at") ? windows.path("5h").get("resets_at").asLong() : null;
                }
            }
        }
        return new Turn(new Reading(five, seven, status, resets, model, first, (System.nanoTime() - t0) / 1_000_000), uuid, text.toString());
    }

    static Request request(CCAPICredential c, String base, String url, String json, String accept) {
        var agent = c.ua() != null && !c.ua().isEmpty() ? c.ua() : AGENT;
        return new Request.Builder().url(url).post(RequestBody.create(json, MediaType.get("application/json")))
            .addHeader("Cookie", "sessionKey=" + c.key()).addHeader("User-Agent", agent).addHeader("Accept", accept)
            .addHeader("Content-Type", "application/json").addHeader("Origin", base).addHeader("Referer", base + "/new").build();
    }

    static String body(Response r) throws IOException {
        var s = r.body() == null ? "" : r.body().string();
        return s.substring(0, Math.min(200, s.length()));
    }

    private FreeGauge() { }
}
