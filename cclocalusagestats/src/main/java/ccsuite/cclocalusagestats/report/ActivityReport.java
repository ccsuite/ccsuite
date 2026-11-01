package ccsuite.cclocalusagestats.report;

import static datapotter.arcadedbhelper.Query.query;

import ccsuite.cclocalusagestats.model.*;
import com.arcadedb.database.Database;
import java.time.Instant;
import java.util.*;

public final class ActivityReport {

    public static final List<String> DIMENSIONS = List.of("project", "prp", "session", "day", "hour");

    private ActivityReport() {}

    /** Active time, with minutes counted once however many sessions ran in them, grouped by project, PRP, session, day or hour of the day. */
    public static List<Map<String, Object>> by(Database db, Filters f, String dim) {
        var sessions = new HashMap<String, SessionInfo>();
        for (var s : query(db, SessionInfo.TYPEDEF)) sessions.put(s.sessionId(), s);
        var groups = new TreeMap<String, Set<Long>>();
        for (var a : query(db, ActiveMinute.TYPEDEF)) {
            var s = sessions.get(a.sessionId());
            if (f.acceptsActivity(a.minute(), s)) groups.computeIfAbsent(key(dim, a, s, f), k -> new HashSet<>()).add(a.minute());
        }
        var list = new ArrayList<>(groups.entrySet());
        if (dim.equals("day")) Collections.reverse(list);
        else if (!dim.equals("hour")) list.sort((x, y) -> y.getValue().size() - x.getValue().size());
        var max = list.stream().mapToInt(e -> e.getValue().size()).max().orElse(1);
        var shown = f.top() > 0 && list.size() > f.top() ? list.subList(0, f.top()) : list;
        return shown.stream().map(e -> row(e.getKey(), e.getValue().size(), dim.equals("hour") ? 30 * e.getValue().size() / max : 0)).toList();
    }

    static String key(String dim, ActiveMinute a, SessionInfo s, Filters f) {
        var project = Labels.project(s == null ? null : s.cwd());
        var at = Instant.ofEpochMilli(a.minute() * 60_000).atZone(f.zone());
        return switch (dim) {
            case "project" -> project;
            case "prp" -> project + (s == null || s.prp() == null ? "" : "  PRP " + s.prp());
            case "session" -> a.sessionId().substring(0, 8) + "  " + project;
            case "day" -> at.toLocalDate().toString();
            case "hour" -> String.format("%02d:00", at.getHour());
            default -> throw new IllegalArgumentException("--by " + DIMENSIONS);
        };
    }

    static Map<String, Object> row(String key, int minutes, int bar) {
        var m = new LinkedHashMap<String, Object>();
        m.put("key", key);
        m.put("activeMinutes", (long) minutes);
        m.put("activeTime", String.format("%d:%02d", minutes / 60, minutes % 60));
        if (bar > 0) m.put("bar", "#".repeat(bar));
        return m;
    }
}
