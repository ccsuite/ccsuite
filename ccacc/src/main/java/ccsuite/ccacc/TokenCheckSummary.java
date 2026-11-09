package ccsuite.ccacc;

import module java.base;
import ccsuite.webproxy.TokenEstimate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * What the measurement log says (PRP 85, R5 and R7): per content type the characters per paid token, whether it sits above or below the free estimate's
 * {@link TokenEstimate#CHARS_PER_TOKEN}, whether the ratios converge across types and stay put over time; and the HTML graph made from it.
 * Above 2.4 means a text has fewer real tokens than the estimate reports (the estimate over-counts, which is safe); below means it has more (the estimate under-counts).
 */
final class TokenCheckSummary {
    static final ObjectMapper JSON = new ObjectMapper();
    static final double LINE = TokenEstimate.CHARS_PER_TOKEN;

    /** The measurement lines of a log (the per-run baseline lines are not measurements). */
    static List<JsonNode> measurements(List<String> logLines) {
        var out = new ArrayList<JsonNode>();
        for (var l : logLines) {
            if (l.isBlank()) continue;
            try { var j = JSON.readTree(l); if (!j.path("kind").asText().equals("baseline") && j.hasNonNull("ratio")) out.add(j); } catch (IOException e) { /* a damaged line is skipped */ }
        }
        return out;
    }

    static double round(double d) { return Math.round(d * 100) / 100.0; }

    static ObjectNode group(String kind, List<JsonNode> rows) {
        var ratios = rows.stream().mapToDouble(r -> r.path("ratio").asDouble()).toArray();
        var chars = rows.stream().mapToDouble(r -> r.path("chars").asDouble()).sum();
        var net = rows.stream().mapToDouble(r -> r.path("net").asDouble()).sum();
        var o = JSON.createObjectNode().put("kind", kind).put("n", rows.size());
        o.put("mean", round(Arrays.stream(ratios).average().orElse(0))).put("min", round(Arrays.stream(ratios).min().orElse(0))).put("max", round(Arrays.stream(ratios).max().orElse(0)));
        var weighted = net == 0 ? 0 : chars / net;
        o.put("weighted", round(weighted)).put("side", weighted >= LINE ? "above" : "below");
        return o;
    }

    static ObjectNode of(List<JsonNode> rows) {
        var s = JSON.createObjectNode();
        var kinds = s.putArray("kinds");
        rows.stream().collect(Collectors.groupingBy(r -> r.path("kind").asText(), TreeMap::new, Collectors.toList())).forEach((k, v) -> kinds.add(group(k, v)));
        s.set("overall", group("all", rows));
        var spread = rows.isEmpty() ? 0 : rows.stream().mapToDouble(r -> r.path("ratio").asDouble()).max().orElse(0) / rows.stream().mapToDouble(r -> r.path("ratio").asDouble()).min().orElse(1);
        // over time: the same text measured in more than one run
        var drift = 0.0;
        var byName = rows.stream().collect(Collectors.groupingBy(r -> r.path("name").asText()));
        for (var v : byName.values()) {
            if (v.stream().map(r -> r.path("run").asText()).distinct().count() < 2) continue;
            var d = v.stream().mapToDouble(r -> r.path("ratio").asDouble()).summaryStatistics();
            drift = Math.max(drift, (d.getMax() - d.getMin()) / d.getMin() * 100);
        }
        var repeated = byName.values().stream().anyMatch(v -> v.stream().map(r -> r.path("run").asText()).distinct().count() > 1);
        s.put("spread", round(spread)).put("driftPercent", round(drift)).put("repeated", repeated);
        s.put("verdict", rows.isEmpty() ? "No measurements yet."
            : (spread <= 1.2 ? "Converges: the ratio is stable across content types (the largest is within 20% of the smallest)."
                : "Diverges by type: the ratio ranges from %.2f to %.2f characters per token across texts.".formatted(rows.stream().mapToDouble(r -> r.path("ratio").asDouble()).min().orElse(0), rows.stream().mapToDouble(r -> r.path("ratio").asDouble()).max().orElse(0)))
            + (repeated ? (drift < 5 ? " Stable over time (same texts measured again moved less than 5%)." : " Drifts over time: the same text moved by %.1f%% between runs.".formatted(drift)) : " Over time: not enough runs yet (every text was measured once)."));
        return s;
    }

    /** The self-contained HTML graph: the data is embedded, the page draws it. */
    static String html(List<JsonNode> rows) throws IOException {
        try (var in = TokenCheckSummary.class.getResourceAsStream("/ccsuite/ccacc/tokencheck.html")) {
            var data = JSON.createObjectNode().put("line", LINE);
            data.putArray("lines").addAll(rows);
            data.set("summary", of(rows));
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("/*DATA*/null", JSON.writeValueAsString(data).replace("</", "<\\/"));
        }
    }

    /** The summary as lines for the terminal. */
    static List<String> text(List<JsonNode> rows) {
        var s = of(rows);
        var out = new ArrayList<String>();
        out.add("%-11s %3s %6s %6s %6s %9s   (characters per paid token; the free estimate uses %.2f)".formatted("type", "n", "mean", "min", "max", "weighted", LINE));
        for (var k : s.path("kinds")) out.add("%-11s %3d %6.2f %6.2f %6.2f %9.2f   %s %.2f".formatted(k.path("kind").asText(), k.path("n").asInt(), k.path("mean").asDouble(), k.path("min").asDouble(), k.path("max").asDouble(), k.path("weighted").asDouble(), k.path("side").asText(), LINE));
        var o = s.path("overall");
        out.add("%-11s %3d %6.2f %6.2f %6.2f %9.2f".formatted("all", o.path("n").asInt(), o.path("mean").asDouble(), o.path("min").asDouble(), o.path("max").asDouble(), o.path("weighted").asDouble()));
        out.add(s.path("verdict").asText());
        var real = rows.stream().filter(r -> r.hasNonNull("actual")).toList();
        if (!real.isEmpty()) {
            out.add("count_tokens against the tokens the server really reported for the same request (the real call spent quota; the count did not):");
            for (var r : real) out.add("  %-26s count %7d  actual %7d  difference %+d  (actual %.2f characters per token)".formatted(r.path("name").asText(), r.path("paid").asInt(), r.path("actual").asInt(), r.path("countError").asInt(), r.path("actualRatio").asDouble()));
            out.add(real.stream().allMatch(r -> r.path("countError").asInt() == 0) ? "The count equalled the real figure every time." : "The count differed from the real figure by up to %d tokens.".formatted(real.stream().mapToInt(r -> Math.abs(r.path("countError").asInt())).max().orElse(0)));
        }
        return out;
    }
}
