package ccsuite.ccacc;

import module java.base;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The persistent, append-only ledger of the FREE lane (PRP 78): one JSON line per call the lane served, in {@code calls-<UTC day>.jsonl}, and one per gauge
 * reading that corrected the estimate, in {@code corrections-<UTC day>.jsonl}, both in the ccacc store folder ({@code ~/ccsuite/ccacc/free-ledger}). It survives a
 * restart of ccacc and of the machine; {@link FreeEstimator#replay} rebuilds each account's room from it. Gauge values are fractions of a window, as the gauge gives them.
 */
final class FreeLedger {

    static final ObjectMapper JSON = new ObjectMapper();

    /**
     * One served call. {@code before*} is the gauge table's reading of the account when its answer began (null when never read), {@code after*} the reading the answer carried
     * (null when it carried none). {@code predicted} is the estimator's cost in points of the five-hour window, {@code observed} the points the gauge actually moved
     * (null when it cannot be told), {@code alone} whether no other call overlapped on that account (only then is {@code observed} the cost of this call). {@code chars} is the size of the request body; {@code promptChars} the size of the prompt the proxy built from it (what the web is charged for; null when not told).
     * {@code cache}: "new" (a chat starts, the whole conversation is sent) or "continued" (a chat the lane holds, only what is new is sent).
     * PRP 88 (null in older lines): {@code replyChars} the characters of the answer (text and tool input), {@code heldChars} what the chat held before this call (prompts sent and replies),
     * {@code gapS} the seconds since the chat's previous call, {@code predictedOther} the cost the model NOT in use predicted (the shadow, so the two compare on the same calls).
     */
    record Call(Instant t, String account, String model, long chars, long tokens, String cache, int status, long ms,
                Double before5, Double before7, Long reset5Before, Double after5, Double after7, Long reset5After,
                double predicted, Double observed, boolean alone, String session, Long promptChars,
                Long replyChars, Long heldChars, Long gapS, Double predictedOther) {

        /** A call without the PRP 88 fields. */
        Call(Instant t, String account, String model, long chars, long tokens, String cache, int status, long ms, Double before5, Double before7, Long reset5Before,
             Double after5, Double after7, Long reset5After, double predicted, Double observed, boolean alone, String session, Long promptChars) {
            this(t, account, model, chars, tokens, cache, status, ms, before5, before7, reset5Before, after5, after7, reset5After, predicted, observed, alone, session, promptChars, null, null, null, null);
        }

        /** A call whose prompt size is not known (the proxy did not say). */
        Call(Instant t, String account, String model, long chars, long tokens, String cache, int status, long ms, Double before5, Double before7, Long reset5Before,
             Double after5, Double after7, Long reset5After, double predicted, Double observed, boolean alone, String session) {
            this(t, account, model, chars, tokens, cache, status, ms, before5, before7, reset5Before, after5, after7, reset5After, predicted, observed, alone, session, null);
        }

        Map<String, Object> toMap() {
            var m = new LinkedHashMap<String, Object>();
            m.put("t", t.toString());
            m.put("account", account);
            m.put("model", model);
            m.put("chars", chars);
            m.put("tokens", tokens);
            m.put("cache", cache);
            m.put("status", status);
            m.put("ms", ms);
            m.put("before5", before5);
            m.put("before7", before7);
            m.put("reset5Before", reset5Before);
            m.put("after5", after5);
            m.put("after7", after7);
            m.put("reset5After", reset5After);
            m.put("predicted", predicted);
            m.put("observed", observed);
            m.put("alone", alone);
            m.put("session", session);
            m.put("promptChars", promptChars);
            if (replyChars != null) m.put("replyChars", replyChars);
            if (heldChars != null) m.put("heldChars", heldChars);
            if (gapS != null) m.put("gapS", gapS);
            if (predictedOther != null) m.put("predictedOther", predictedOther);
            return m;
        }

        static Call of(JsonNode j) {
            return new Call(Instant.parse(j.path("t").asText()), text(j, "account"), text(j, "model"), j.path("chars").asLong(), j.path("tokens").asLong(), text(j, "cache"),
                    j.path("status").asInt(), j.path("ms").asLong(), dbl(j, "before5"), dbl(j, "before7"), lng(j, "reset5Before"), dbl(j, "after5"), dbl(j, "after7"), lng(j, "reset5After"),
                    j.path("predicted").asDouble(), dbl(j, "observed"), j.path("alone").asBoolean(), text(j, "session"), lng(j, "promptChars"),
                    lng(j, "replyChars"), lng(j, "heldChars"), lng(j, "gapS"), dbl(j, "predictedOther"));
        }
    }

    /** A gauge reading that moved the estimate: what the estimate was, what the gauge said, the difference (points of the five-hour window); {@code source} is "probe" or "call". */
    record Correction(Instant t, String account, String source, double estimate5, double reading5, double correction5, Double reading7, Long reset5) {

        Map<String, Object> toMap() {
            var m = new LinkedHashMap<String, Object>();
            m.put("t", t.toString());
            m.put("account", account);
            m.put("source", source);
            m.put("estimate5", estimate5);
            m.put("reading5", reading5);
            m.put("correction5", correction5);
            m.put("reading7", reading7);
            m.put("reset5", reset5);
            return m;
        }

        static Correction of(JsonNode j) {
            return new Correction(Instant.parse(j.path("t").asText()), text(j, "account"), text(j, "source"), j.path("estimate5").asDouble(), j.path("reading5").asDouble(),
                    j.path("correction5").asDouble(), dbl(j, "reading7"), lng(j, "reset5"));
        }
    }

    /** One ping of the ping round (PRP 79), in {@code pings-<UTC day>.jsonl}: why the account was due, and the gauge the ping read ({@code problem} set and the gauge null when it failed). */
    record Ping(Instant t, String account, String why, Double five, Double seven, Long reset5, String status, String problem) {

        Map<String, Object> toMap() {
            var m = new LinkedHashMap<String, Object>();
            m.put("t", t.toString());
            m.put("account", account);
            m.put("why", why);
            m.put("u5h", five);
            m.put("u7d", seven);
            m.put("reset5h", reset5);
            m.put("status", status);
            m.put("problem", problem);
            return m;
        }
    }

    final Path dir;

    FreeLedger(Path dir) { this.dir = dir; }

    static FreeLedger standard() { return new FreeLedger(Path.of(System.getProperty("user.home"), "ccsuite", "ccacc", "free-ledger")); }

    synchronized void append(Call c) { write("calls-", c.t(), c.toMap()); }

    synchronized void append(Correction c) { write("corrections-", c.t(), c.toMap()); }

    synchronized void append(Ping p) { write("pings-", p.t(), p.toMap()); }

    /** The successful pings from {@code since} on, in time order; a failed ping (no gauge) and a torn line are skipped. */
    List<Ping> pings(Instant since) {
        var out = new ArrayList<Ping>();
        for (var l : lines("pings-", since)) {
            try {
                var j = JSON.readTree(l);
                var p = new Ping(Instant.parse(j.path("t").asText()), text(j, "account"), text(j, "why"), dbl(j, "u5h"), dbl(j, "u7d"), lng(j, "reset5h"), text(j, "status"), text(j, "problem"));
                if (p.five() != null && (since == null || !p.t().isBefore(since))) out.add(p);
            } catch (IOException | RuntimeException e) { /* a torn line */ }
        }
        out.sort(Comparator.comparing(Ping::t));
        return out;
    }

    /** The latest reading of each account in the ledger from {@code since} on, from the calls (the gauge their answer carried), the corrections (probe and call readings alike) and the pings (a ping that agrees with the estimate leaves no correction, only its own line). */
    List<Seed> latestReadings(Instant since) {
        var latest = new LinkedHashMap<String, Seed>();
        calls(since).forEach(c -> { if (c.after5() != null) latest.merge(c.account(), new Seed(c.account(), c.t(), c.after5(), c.after7(), c.reset5After()), Seed::newer); });
        corrections(since).forEach(c -> latest.merge(c.account(), new Seed(c.account(), c.t(), c.reading5() / 100, c.reading7() == null ? null : c.reading7() / 100, c.reset5()), Seed::newer));   // a correction holds points, a call a fraction
        pings(since).forEach(p -> latest.merge(p.account(), new Seed(p.account(), p.t(), p.five(), p.seven(), p.reset5()), Seed::newer));
        return List.copyOf(latest.values());
    }

    /** A reading read back from the ledger, as fractions of a window. */
    record Seed(String account, Instant t, Double five, Double seven, Long reset5) {
        Seed newer(Seed o) { return o.t.isAfter(t) ? o : this; }
    }

    void write(String prefix, Instant t, Map<String, Object> line) {
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(prefix + t.atZone(ZoneOffset.UTC).toLocalDate() + ".jsonl"), JSON.writeValueAsString(line) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("ccacc: free ledger not written: " + e.getMessage());
        }
    }

    /** The calls from {@code since} on (null: all), in time order; a torn or unreadable line is skipped. */
    List<Call> calls(Instant since) {
        var out = new ArrayList<Call>();
        for (var l : lines("calls-", since)) {
            try { var c = Call.of(JSON.readTree(l)); if (since == null || !c.t().isBefore(since)) out.add(c); } catch (IOException | RuntimeException e) { /* a torn line */ }
        }
        out.sort(Comparator.comparing(Call::t));
        return out;
    }

    List<Correction> corrections(Instant since) {
        var out = new ArrayList<Correction>();
        for (var l : lines("corrections-", since)) {
            try { var c = Correction.of(JSON.readTree(l)); if (since == null || !c.t().isBefore(since)) out.add(c); } catch (IOException | RuntimeException e) { /* a torn line */ }
        }
        out.sort(Comparator.comparing(Correction::t));
        return out;
    }

    /** The lines of the day files that can hold something from {@code since} on (the day files are named by UTC day). */
    List<String> lines(String prefix, Instant since) {
        var out = new ArrayList<String>();
        if (!Files.isDirectory(dir)) return out;
        var firstDay = since == null ? null : since.atZone(ZoneOffset.UTC).toLocalDate().toString();
        try (var files = Files.list(dir)) {
            for (var f : files.filter(p -> p.getFileName().toString().startsWith(prefix)).sorted().toList()) {
                var day = f.getFileName().toString().substring(prefix.length(), prefix.length() + 10);
                if (firstDay != null && day.compareTo(firstDay) < 0) continue;
                for (var l : Files.readAllLines(f, StandardCharsets.UTF_8)) if (!l.isBlank()) out.add(l);
            }
        } catch (IOException e) {
            System.err.println("ccacc: free ledger not read: " + e.getMessage());
        }
        return out;
    }

    static String text(JsonNode j, String f) { return j.hasNonNull(f) ? j.get(f).asText() : null; }

    static Double dbl(JsonNode j, String f) { return j.hasNonNull(f) ? j.get(f).asDouble() : null; }

    static Long lng(JsonNode j, String f) { return j.hasNonNull(f) ? j.get(f).asLong() : null; }
}
