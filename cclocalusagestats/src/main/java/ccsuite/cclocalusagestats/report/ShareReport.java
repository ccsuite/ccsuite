package ccsuite.cclocalusagestats.report;

import static datapotter.arcadedbhelper.Query.query;

import ccsuite.cclocalusagestats.model.*;
import com.arcadedb.database.Database;
import java.time.*;
import java.util.*;

/**
 * How much of each account's 5-hour quota this machine used, and how much others did.
 * Per window of one account: consumed = the rise of the account's own reading between the first and last reading seen;
 * mine = the points of this machine's messages on that account in the same interval; others = consumed - mine, never below zero.
 */
public final class ShareReport {

    static final long SAME_WINDOW_MS = 10 * 60_000;

    private ShareReport() {}

    record Window(String account, long from, long to, double consumed) {}

    public static List<Map<String, Object>> run(Database db, Filters f, boolean byDay) {
        var readings = new ArrayList<Reading>();
        for (var r : query(db, Reading.TYPEDEF).orderByAsc(Reading_A.$ts)) if (f.acceptsDay(r.ts())) readings.add(r);
        if (readings.isEmpty()) return List.of();
        var rows = new ArrayList<UsageRow>();
        for (var r : query(db, UsageRow.TYPEDEF).ge(UsageRow_A.$ts, readings.get(0).ts())) rows.add(r);
        return compute(readings, rows, f.zone(), byDay);
    }

    public static List<Map<String, Object>> compute(List<Reading> readings, List<UsageRow> rows, ZoneId zone, boolean byDay) {
        var groups = new TreeMap<String, double[]>();
        for (var w : windows(readings)) {
            var mine = rows.stream().filter(r -> w.account().equals(r.account()) && r.ts() >= w.from() && r.ts() <= w.to()).mapToDouble(Quota::points).sum();
            var key = byDay ? Instant.ofEpochMilli(w.from()).atZone(zone).toLocalDate().toString() : w.account();
            var g = groups.computeIfAbsent(key, k -> new double[3]);
            g[0]++;
            g[1] += w.consumed();
            g[2] += mine;
        }
        var out = new ArrayList<Map<String, Object>>();
        var all = new double[3];
        groups.forEach((k, g) -> { out.add(row(k, g)); for (var i = 0; i < 3; i++) all[i] += g[i]; });
        if (byDay) Collections.reverse(out);
        out.add(row("ALL", all));
        return out;
    }

    static List<Window> windows(List<Reading> readings) {
        var byAccount = new TreeMap<String, List<Reading>>();
        for (var r : readings) if (r.u5h() != null) byAccount.computeIfAbsent(r.account(), k -> new ArrayList<>()).add(r);
        var out = new ArrayList<Window>();
        byAccount.forEach((account, list) -> {
            list.sort(Comparator.comparingLong(Reading::ts));
            var first = list.get(0);
            var prev = first;
            for (var r : list.subList(1, list.size())) {
                if (newWindow(prev, r)) { close(out, account, first, prev); first = r; }
                prev = r;
            }
            close(out, account, first, prev);
        });
        return out;
    }

    static boolean newWindow(Reading a, Reading b) {
        return b.u5h() < a.u5h() - 5
            || (a.reset5h() != null && b.reset5h() != null && Math.abs(b.reset5h() - a.reset5h()) > SAME_WINDOW_MS);
    }

    static void close(List<Window> out, String account, Reading first, Reading last) {
        if (last.ts() > first.ts()) out.add(new Window(account, first.ts(), last.ts(), Math.max(0, last.u5h() - first.u5h())));
    }

    static Map<String, Object> row(String key, double[] g) {
        var m = new LinkedHashMap<String, Object>();
        m.put("key", key);
        m.put("windows", (long) g[0]);
        m.put("consumed", Math.round(g[1] * 10) / 10.0);
        m.put("mine", Math.round(g[2] * 10) / 10.0);
        m.put("others", Math.round(Math.max(0, g[1] - g[2]) * 10) / 10.0);
        m.put("mineShare", g[1] <= 0 ? "-" : Math.min(100, Math.round(100 * g[2] / g[1])) + "%");
        return m;
    }
}
