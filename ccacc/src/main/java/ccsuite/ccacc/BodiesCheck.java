package ccsuite.ccacc;

import module java.base;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * PRP 87 R4, {@code ccacc tokencheck model bodies [--session ID]}: every call of the request log, rebuilt by replaying its lines, counted with count_tokens (the counter is a parameter),
 * printed as the estimate the lane made at the time against the real count. Read-only; a call that cannot be counted is said so and the rest go on.
 */
final class BodiesCheck {

    static List<String> report(Path dir, String session, ToIntFunction<ObjectNode> counter) throws IOException {
        var out = new ArrayList<String>();
        var sessions = session != null ? List.of(session) : BodyLog.sessions(dir);
        var attempted = 0; var counted = 0;
        var estimates = 0.0; var reals = 0.0; var errors = 0.0; var absolute = 0.0;
        for (var s : sessions) {
            var calls = BodyLog.replay(dir, s);
            if (calls.isEmpty()) continue;
            out.add("session " + s + " (" + calls.size() + " logged)");
            var i = 0;
            for (var c : calls) {
                attempted++;
                var estimate = c.meta().path("tokens").asLong();
                try {
                    var real = counter.applyAsInt(countable(c.request()));
                    var error = (estimate - real) * 100.0 / Math.max(1, real);
                    out.add("  #%d %s zone %s estimate %d real %d error %+.1f%%".formatted(++i, c.meta().path("t").asText(), c.meta().path("zone").asText(), estimate, real, error));
                    counted++; estimates += estimate; reals += real; errors += error; absolute += Math.abs(error);
                } catch (RuntimeException e) {
                    out.add("  #%d %s not counted: %s".formatted(++i, c.meta().path("t").asText(), e.getMessage()));
                }
            }
        }
        if (attempted == 0) return List.of("no calls in the log " + dir + (session == null ? "" : " for session " + session));
        out.add("%d calls counted of %d, mean error %+.1f%%, mean absolute error %.1f%%, total estimate %+.1f%% of the real count".formatted(counted, attempted,
            counted == 0 ? 0 : errors / counted, counted == 0 ? 0 : absolute / counted, reals == 0 ? 0 : (estimates / reals - 1) * 100));
        return out;
    }

    /** What count_tokens takes of a request: model, system, tools and messages. */
    static ObjectNode countable(com.fasterxml.jackson.databind.JsonNode request) {
        var b = BodyLog.J.createObjectNode();
        for (var k : List.of("model", "system", "tools")) if (request.has(k)) b.set(k, request.get(k));
        if (request.has("messages")) {
            var messages = b.putArray("messages");
            for (var m : request.get("messages")) {
                var copy = m.deepCopy();
                if (copy instanceof ObjectNode o) o.remove("output_config");   // a per-message setting of Claude Code that count_tokens refuses (400, real request 2026-11-08)
                messages.add(copy);
            }
        }
        return b;
    }

    private BodiesCheck() { }
}
