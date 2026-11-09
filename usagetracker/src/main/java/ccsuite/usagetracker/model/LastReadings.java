package ccsuite.usagetracker.model;

import module java.base;
import ccsuite.base.model.CCAPICredential;
import ccsuite.base.model.Usage;
import ccsuite.base.model.UsageData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The last reading of each paid account, read back from the log the account watcher writes ({@code ~/ccsuite/ccacc/readings/<UTC day>.jsonl}: one line whenever an
 * account's reading changes). An account the usage endpoint says nothing about (it is used up, or the call failed) is not dead: its last reading still holds the
 * time its windows reset, which is when it comes back. A page made from it carries the time of the reading, so the views say how old it is. The log is written on
 * change only, so the time is that of the last CHANGE: the true age of an unchanged reading is less.
 */
public final class LastReadings {

    /** One line of the log: the time, the account, the usage and the reset of each window. */
    public record Reading(String account, Instant t, Double u5h, Double u7d, OffsetDateTime reset5h, OffsetDateTime reset7d) { }

    static final ObjectMapper JSON = new ObjectMapper();

    public static Path standard() { return Path.of(System.getProperty("user.home"), "ccsuite", "ccacc", "readings"); }

    /** The last line of every account over the newest {@code days} day files; an unreadable file or line is skipped, a missing folder gives nothing. */
    public static Map<String, Reading> load(Path dir, int days) {
        var out = new LinkedHashMap<String, Reading>();
        if (!Files.isDirectory(dir)) return out;
        try (var files = Files.list(dir)) {
            var newest = files.filter(f -> f.getFileName().toString().endsWith(".jsonl")).sorted(Comparator.reverseOrder()).limit(days).sorted().toList();
            for (var f : newest) read(f, out);
        } catch (IOException e) {
            System.err.println("ccacc: readings not read back: " + e.getMessage());
        }
        return out;
    }

    static void read(Path file, Map<String, Reading> out) {
        try {
            for (var line : Files.readAllLines(file)) {
                try {
                    var j = JSON.readTree(line);
                    if (j == null || !j.hasNonNull("account") || !j.hasNonNull("t")) continue;
                    out.put(j.get("account").asText(), new Reading(j.get("account").asText(), Instant.parse(j.get("t").asText()), num(j, "u5h"), num(j, "u7d"), at(j, "reset5h"), at(j, "reset7d")));
                } catch (IOException | RuntimeException skipped) { }
            }
        } catch (IOException e) {
            System.err.println("ccacc: readings file not read: " + file + ": " + e.getMessage());
        }
    }

    static Double num(JsonNode j, String key) { return j.hasNonNull(key) ? j.get(key).asDouble() : null; }

    static OffsetDateTime at(JsonNode j, String key) { return j.hasNonNull(key) ? OffsetDateTime.parse(j.get(key).asText()) : null; }

    /** The page for an account with no live reading: its last reading, dated when it was taken. A window whose reset has passed since is back at zero. */
    public static AccountUsage page(CCAPICredential credential, Reading r, Instant now) {
        var data = new UsageData();
        if (r.u5h() != null) data.fiveHour(window(r.u5h(), r.reset5h(), now));
        if (r.u7d() != null) data.sevenDay(window(r.u7d(), r.reset7d(), now));
        return new AccountUsage(credential, data).fetchedAt(OffsetDateTime.ofInstant(r.t(), ZoneOffset.UTC));
    }

    static Usage window(double used, OffsetDateTime reset, Instant now) {
        var passed = reset != null && !reset.toInstant().isAfter(now);
        return new Usage().utilization(passed ? 0 : used).resetsAt(passed ? null : reset);
    }

    private LastReadings() {}
}
