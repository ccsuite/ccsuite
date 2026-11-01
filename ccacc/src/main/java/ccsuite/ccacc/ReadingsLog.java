package ccsuite.ccacc;

import module java.base;
import ccsuite.oauthclient.AccountUsage;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Writes down the accounts' own quota readings as the watcher sees them: one JSON line per account whenever its
 * reading changes, in ~/ccsuite/ccacc/readings/&lt;UTC day&gt;.jsonl. Append only; nothing reads it back here.
 * Without this the readings exist only in memory and a day of history is lost for good.
 */
final class ReadingsLog {

    static final ObjectMapper JSON = new ObjectMapper();

    final Path dir;
    final Map<String, String> last = new HashMap<>();

    ReadingsLog(Path dir) {
        this.dir = dir;
    }

    static ReadingsLog standard() {
        return new ReadingsLog(Path.of(System.getProperty("user.home"), "ccsuite", "ccacc", "readings"));
    }

    synchronized void record(String activeId, Map<String, AccountUsage> usages) {
        var now = Instant.now();
        var lines = new StringBuilder();
        usages.forEach((id, u) -> {
            if (u == null || (u.fiveHourPct() == null && u.sevenDayPct() == null)) return;
            var sig = u.fiveHourPct() + "|" + u.sevenDayPct() + "|" + u.fiveHourResetsAt() + "|" + u.sevenDayResetsAt() + "|" + id.equals(activeId);
            if (sig.equals(last.put(id, sig))) return;
            var m = new LinkedHashMap<String, Object>();
            m.put("t", now.toString());
            m.put("account", id);
            m.put("active", id.equals(activeId));
            m.put("u5h", u.fiveHourPct());
            m.put("u7d", u.sevenDayPct());
            m.put("reset5h", u.fiveHourResetsAt());
            m.put("reset7d", u.sevenDayResetsAt());
            try { lines.append(JSON.writeValueAsString(m)).append('\n'); } catch (IOException ignored) { }
        });
        if (lines.isEmpty()) return;
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(now.atZone(ZoneOffset.UTC).toLocalDate() + ".jsonl"), lines,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("ccacc: readings not written: " + e.getMessage());
        }
    }
}
