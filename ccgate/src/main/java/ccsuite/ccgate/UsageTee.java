package ccsuite.ccgate;

import module java.base;
import com.fasterxml.jackson.databind.*;

/**
 * Copies an answer to the caller chunk by chunk (flushing each, so streaming stays live) and reads the usage out of it
 * on the way: for a stream only the message_start and message_delta events are parsed, nothing is kept; a plain JSON
 * answer is small and is read whole at the end.
 */
public final class UsageTee {

    static final ObjectMapper JSON = new ObjectMapper();
    static final int JSON_LIMIT = 64 << 20;

    public static Answer pump(InputStream in, OutputStream out, boolean stream) throws IOException {
        var answer = new Answer();
        var lines = new ByteArrayOutputStream();
        var whole = stream ? null : new ByteArrayOutputStream();
        var buf = new byte[8192];
        for (int n; (n = in.read(buf)) != -1; ) {
            out.write(buf, 0, n);
            out.flush();
            answer.bytes(answer.bytes() + n);
            if (stream) scan(buf, n, lines, answer);
            else if (whole.size() < JSON_LIMIT) whole.write(buf, 0, n);
        }
        if (stream && lines.size() > 0) line(lines.toString(StandardCharsets.UTF_8), answer);
        if (!stream) readWhole(whole.toByteArray(), answer);
        return answer;
    }

    static void scan(byte[] buf, int n, ByteArrayOutputStream lines, Answer answer) {
        for (var i = 0; i < n; i++) {
            if (buf[i] == '\n') {
                line(lines.toString(StandardCharsets.UTF_8), answer);
                lines.reset();
            } else lines.write(buf[i]);
        }
    }

    static void line(String line, Answer answer) {
        if (!line.startsWith("data:") || !(line.contains("message_start") || line.contains("message_delta"))) return;
        try {
            var ev = JSON.readTree(line.substring(5).strip());
            switch (ev.path("type").asText()) {
                case "message_start" -> fromMessage(ev.path("message"), answer);
                case "message_delta" -> {
                    if (ev.path("delta").hasNonNull("stop_reason")) answer.stopReason(ev.path("delta").path("stop_reason").asText());
                    merge(ev.path("usage"), answer.usage());
                }
                default -> { }
            }
        } catch (IOException ignored) {
        }
    }

    static void readWhole(byte[] body, Answer answer) {
        try {
            var msg = JSON.readTree(body);
            if (msg != null && msg.has("usage")) fromMessage(msg, answer);
            if (msg != null && msg.hasNonNull("stop_reason")) answer.stopReason(msg.path("stop_reason").asText());
        } catch (IOException ignored) {
        }
    }

    static void fromMessage(JsonNode msg, Answer answer) {
        answer.messageId(msg.path("id").asText(null)).model(msg.path("model").asText(null));
        merge(msg.path("usage"), answer.usage());
    }

    /** Later events repeat the counters cumulatively, so a non-zero value replaces the earlier one. */
    static void merge(JsonNode u, Usage into) {
        if (u.isMissingNode()) return;
        set(u, "input_tokens", into::inputTokens);
        set(u, "output_tokens", into::outputTokens);
        set(u, "cache_creation_input_tokens", into::cacheCreationInputTokens);
        set(u, "cache_read_input_tokens", into::cacheReadInputTokens);
        set(u.path("cache_creation"), "ephemeral_5m_input_tokens", into::cacheCreation5m);
        set(u.path("cache_creation"), "ephemeral_1h_input_tokens", into::cacheCreation1h);
    }

    static void set(JsonNode u, String field, LongFunction<?> to) {
        var v = u.path(field).asLong(0);
        if (v > 0) to.apply(v);
    }
}
