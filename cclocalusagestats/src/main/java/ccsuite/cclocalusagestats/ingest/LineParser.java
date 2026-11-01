package ccsuite.cclocalusagestats.ingest;

import static com.fasterxml.jackson.core.JsonToken.*;

import ccsuite.cclocalusagestats.model.UsageRow;
import com.fasterxml.jackson.core.*;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

public final class LineParser {

    static final JsonFactory JSON = new JsonFactory();
    static final Pattern PRP = Pattern.compile("prp[/\\\\]+(\\d{1,3})-prp");
    static final String USER_TEXT = "\"role\":\"user\",\"content\":\"";

    private LineParser() {}

    public static UsageRow parse(String line) {
        var r = new UsageRow();
        try (var p = JSON.createParser(line)) {
            if (p.nextToken() != START_OBJECT) return null;
            while (p.nextToken() == FIELD_NAME) {
                var name = p.currentName();
                var t = p.nextToken();
                switch (name) {
                    case "timestamp" -> r.ts(Instant.parse(p.getText()).toEpochMilli());
                    case "sessionId" -> r.sessionId(p.getText());
                    case "cwd" -> r.cwd(p.getText());
                    case "entrypoint" -> r.entrypoint(p.getText());
                    case "version" -> r.version(p.getText());
                    case "requestId" -> r.requestId(p.getText());
                    case "isSidechain" -> r.sidechain(t == VALUE_TRUE);
                    case "message" -> message(p, r);
                    default -> p.skipChildren();
                }
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
        if (r.ts() == null) return null;
        if (r.inputTokens() != null) finishUsage(r);
        else r.prp(prpOf(line));
        return r;
    }

    static String prpOf(String line) {
        var i = line.indexOf(USER_TEXT);
        if (i < 0) return null;
        var m = PRP.matcher(line.substring(i, Math.min(line.length(), i + 1500)));
        return m.find() ? m.group(1) : null;
    }

    static void message(JsonParser p, UsageRow r) throws IOException {
        if (p.currentToken() != START_OBJECT) { p.skipChildren(); return; }
        while (p.nextToken() == FIELD_NAME) {
            var name = p.currentName();
            p.nextToken();
            switch (name) {
                case "id" -> r.messageId(p.getText());
                case "model" -> r.model(p.getText());
                case "usage" -> usage(p, r);
                default -> p.skipChildren();
            }
        }
    }

    static void usage(JsonParser p, UsageRow r) throws IOException {
        if (p.currentToken() != START_OBJECT) { p.skipChildren(); return; }
        var m = new HashMap<String, Long>();
        walk(p, "", m, r);
        r.inputTokens(m.getOrDefault("input_tokens", 0L))
            .outputTokens(m.getOrDefault("output_tokens", 0L))
            .thinkingTokens(m.getOrDefault("output_tokens_details.thinking_tokens", 0L))
            .cacheWriteTotal(m.getOrDefault("cache_creation_input_tokens", 0L))
            .cacheWrite5m(m.getOrDefault("cache_creation.ephemeral_5m_input_tokens", 0L))
            .cacheWrite1h(m.getOrDefault("cache_creation.ephemeral_1h_input_tokens", 0L))
            .cacheRead(m.getOrDefault("cache_read_input_tokens", 0L))
            .webSearches(m.getOrDefault("server_tool_use.web_search_requests", 0L));
    }

    static void walk(JsonParser p, String prefix, Map<String, Long> m, UsageRow r) throws IOException {
        while (p.nextToken() == FIELD_NAME) {
            var key = prefix + p.currentName();
            var t = p.nextToken();
            if (t == START_OBJECT) walk(p, key + ".", m, r);
            else if (t == START_ARRAY) p.skipChildren();
            else if (t.isNumeric()) m.put(key, p.getLongValue());
            else if (t == VALUE_STRING && key.equals("speed")) r.speed(p.getText());
        }
    }

    static void finishUsage(UsageRow r) {
        var id = r.messageId();
        if (id == null || r.total() == 0) { r.inputTokens(null); return; }
        var anthropicId = id.startsWith("msg_") || id.startsWith("resp_");
        r.key(r.requestId() != null ? id + "|" + r.requestId()
            : id + "|" + r.sessionId() + (anthropicId ? "" : "|" + r.ts()));
    }
}
