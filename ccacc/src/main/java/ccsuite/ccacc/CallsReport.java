package ccsuite.ccacc;

import module java.base;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * What a run through the free lane cost, read back from the proxy's call log (one JSON line per served call): per group the calls, how many were ok, how many
 * continued a chat against started a new one, the characters sent, the median and total time, and per account the five-hour gauge from the first call to the last
 * (the gauge moves in steps of one point, so judge a run by that span). Pure: lines in, text or JSON out.
 */
final class CallsReport {

    static final ObjectMapper JSON = new ObjectMapper();

    /** The calls of the file in order; a line that is not JSON is skipped (a half-written last line). */
    static List<JsonNode> parse(List<String> lines) {
        var out = new ArrayList<JsonNode>();
        for (var l : lines) {
            if (l.isBlank()) continue;
            try { out.add(JSON.readTree(l)); } catch (IOException e) { /* a torn line */ }
        }
        return out;
    }

    /** "30m", "2h", "1d", "90s". */
    static Duration since(String s) {
        var m = java.util.regex.Pattern.compile("(\\d+)([smhd])").matcher(s.trim());
        if (!m.matches()) throw new IllegalArgumentException("--since takes a number and s, m, h or d, like 30m: " + s);
        var n = Long.parseLong(m.group(1));
        return switch (m.group(2)) { case "s" -> Duration.ofSeconds(n); case "m" -> Duration.ofMinutes(n); case "h" -> Duration.ofHours(n); default -> Duration.ofDays(n); };
    }

    /** Both logs together in time order (the timestamps are ISO UTC, so they sort as text). */
    static List<JsonNode> sorted(List<JsonNode> calls) {
        return calls.stream().sorted(Comparator.comparing((JsonNode c) -> c.path("ts").asText(""))).toList();
    }

    static List<JsonNode> filter(List<JsonNode> calls, String job, Instant from) {
        return calls.stream().filter(c -> job == null || job.equals(c.path("job").asText(null)))
                .filter(c -> from == null || instant(c).map(t -> !t.isBefore(from)).orElse(true)).toList();
    }

    static Optional<Instant> instant(JsonNode c) {
        try { return Optional.of(Instant.parse(c.path("ts").asText())); } catch (RuntimeException e) { return Optional.empty(); }
    }

    static String key(JsonNode c, String by) {
        var v = c.path(by).asText(null);
        return v == null || v.isBlank() ? "(none)" : v;
    }

    /** One group's numbers. */
    record Group(String key, int calls, int ok, int continued, int fresh, long promptChars, long replyChars, long medianMs, long totalMs, Map<String, int[]> gauge5h, Map<String, int[]> gauge7d, Map<String, Integer> why) {}

    static List<Group> groups(List<JsonNode> calls, String by) {
        var map = new LinkedHashMap<String, List<JsonNode>>();
        calls.forEach(c -> map.computeIfAbsent(key(c, by), k -> new ArrayList<>()).add(c));
        return map.entrySet().stream().map(e -> group(e.getKey(), e.getValue())).toList();
    }

    static Group group(String key, List<JsonNode> list) {
        var ms = list.stream().mapToLong(c -> c.path("ms").asLong()).sorted().toArray();
        var median = ms.length == 0 ? 0 : ms.length % 2 == 1 ? ms[ms.length / 2] : (ms[ms.length / 2 - 1] + ms[ms.length / 2]) / 2;
        return new Group(key, list.size(), (int) list.stream().filter(c -> c.path("ok").asBoolean()).count(),
                (int) list.stream().filter(c -> "continued".equals(c.path("mode").asText())).count(), (int) list.stream().filter(c -> "fresh".equals(c.path("mode").asText())).count(),
                list.stream().mapToLong(c -> c.path("promptChars").asLong()).sum(), list.stream().mapToLong(c -> c.path("replyChars").asLong()).sum(),
                median, Arrays.stream(ms).sum(), span(list, "gauge5h"), span(list, "gauge7d"), whys(list));
    }

    /** Why calls started a new chat (the proxy's `why` field: first call of the session, account changed, message N differs at char K, ...), counted; numbers are blanked so alike reasons add up. */
    static Map<String, Integer> whys(List<JsonNode> list) {
        var out = new LinkedHashMap<String, Integer>();
        for (var c : list) {
            if ("continued".equals(c.path("mode").asText()) || !c.hasNonNull("why")) continue;
            out.merge(c.get("why").asText().replaceAll("\\d+", "N"), 1, Integer::sum);
        }
        return out;
    }

    /** Per account: {first, last} of a gauge over the calls that carried it. */
    static Map<String, int[]> span(List<JsonNode> list, String field) {
        var out = new LinkedHashMap<String, int[]>();
        for (var c : list) {
            if (!c.hasNonNull(field)) continue;
            var v = c.get(field).asInt();
            out.merge(c.path("account").asText("?"), new int[]{v, v}, (a, b) -> new int[]{a[0], b[1]});
        }
        return out;
    }

    static List<String> text(List<Group> groups, String by) {
        var out = new ArrayList<String>();
        if (groups.isEmpty()) { out.add("No calls in the log for that selection."); return out; }
        for (var g : groups) {
            out.add(by + " " + g.key() + ":  " + g.calls() + " calls, " + g.ok() + " ok, " + (g.calls() - g.ok()) + " failed;  continued " + g.continued() + ", fresh " + g.fresh() + ", new " + (g.calls() - g.continued() - g.fresh()));
            out.add("    sent " + g.promptChars() + " chars, received " + g.replyChars() + ";  median " + g.medianMs() + " ms, total " + g.totalMs() / 1000 + " s");
            if (!g.why().isEmpty()) out.add("    new chats because: " + g.why().entrySet().stream().map(e -> e.getKey() + " x" + e.getValue()).collect(java.util.stream.Collectors.joining("; ")));
            g.gauge5h().forEach((acc, s) -> out.add("    " + acc + "  5h gauge " + s[0] + "% -> " + s[1] + "%  (" + (s[1] - s[0]) + " points)"
                    + (g.gauge7d().containsKey(acc) ? ",  week " + g.gauge7d().get(acc)[0] + "% -> " + g.gauge7d().get(acc)[1] + "%" : "")));
        }
        return out;
    }

    static String json(List<Group> groups) {
        var root = JSON.createArrayNode();
        for (var g : groups) {
            var o = root.addObject();
            o.put("key", g.key()).put("calls", g.calls()).put("ok", g.ok()).put("failed", g.calls() - g.ok()).put("continued", g.continued()).put("fresh", g.fresh())
                    .put("new", g.calls() - g.continued() - g.fresh()).put("promptChars", g.promptChars()).put("replyChars", g.replyChars()).put("medianMs", g.medianMs()).put("totalMs", g.totalMs());
            var why = o.putObject("newChatBecause");
            g.why().forEach(why::put);
            var accounts = o.putObject("accounts");
            g.gauge5h().forEach((acc, s) -> {
                var a = accounts.putObject(acc);
                a.put("gauge5hFirst", s[0]).put("gauge5hLast", s[1]).put("points", s[1] - s[0]);
                var w = g.gauge7d().get(acc);
                if (w != null) a.put("gauge7dFirst", w[0]).put("gauge7dLast", w[1]);
            });
        }
        return root.toPrettyString();
    }

    // ---- what needs attention (PRP 78 R6): read-only, for the fclaude monitor

    /** The calls of a selection that failed, and the ones that took longer than the limit. */
    record Attention(List<JsonNode> failed, List<JsonNode> slow, long slowMs) {}

    static Attention attention(List<JsonNode> calls, long slowMs) {
        return new Attention(calls.stream().filter(c -> c.has("ok") && !c.path("ok").asBoolean()).toList(), calls.stream().filter(c -> c.path("ok").asBoolean(true) && c.path("ms").asLong() > slowMs).toList(), slowMs);
    }

    static String when(JsonNode c) { return instant(c).map(t -> t.truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString()).orElse("?"); }

    /** {@code open}: the calls running now as the app lists them ({@code ccacc lanes open}), empty when no app answered. */
    static List<String> attentionText(Attention a, Optional<List<String>> open) {
        var out = new ArrayList<String>();
        out.add("failed (" + a.failed().size() + "):");
        a.failed().forEach(c -> out.add("  " + when(c) + "  " + key(c, "account") + "  " + key(c, "model") + "  " + key(c, "failure") + "  " + c.path("ms").asLong() / 1000 + " s" + (c.hasNonNull("error") ? "  " + c.get("error").asText() : "")));
        out.add("slow (" + a.slow().size() + ", over " + a.slowMs() / 1000 + " s):");
        a.slow().forEach(c -> out.add("  " + when(c) + "  " + key(c, "account") + "  " + key(c, "model") + "  " + c.path("ms").asLong() / 1000 + " s  sent " + c.path("promptChars").asLong() + " chars"));
        if (open.isEmpty()) out.add("open calls: unknown (ccacc is not running, or did not answer)");
        else {
            out.add("still open (" + open.get().size() + "):");
            open.get().forEach(l -> out.add("  " + l));
        }
        return out;
    }

    static String attentionJson(Attention a, Optional<List<String>> open) {
        var root = JSON.createObjectNode();
        var failed = root.putArray("failed");
        a.failed().forEach(failed::add);
        var slow = root.putArray("slow");
        a.slow().forEach(slow::add);
        if (open.isPresent()) { var o = root.putArray("open"); open.get().forEach(o::add); }
        else root.putNull("open");
        return root.toPrettyString();
    }

    private CallsReport() {}
}
