package ccsuite.cclocalusagestats.db;

import static datapotter.arcadedbhelper.Query.query;

import ccsuite.cclocalusagestats.model.*;
import ccsuite.cclocalusagestats.report.Labels;
import com.arcadedb.database.Database;
import java.time.*;
import java.util.*;

/** What a deletion would remove, countable before it is run. Transcripts are never touched. */
public final class DeletePlan {

    record Step(String type, String where, Object[] params) {}

    final List<Step> steps;

    DeletePlan(List<Step> steps) {
        this.steps = steps;
    }

    public static DeletePlan everything() {
        return new DeletePlan(List.of("ActiveMinute", "UsageRow", "SessionInfo", "FileState", "SwitchEvent", "Reading").stream()
            .map(t -> new Step(t, "", new Object[0])).toList());
    }

    public static DeletePlan session(String prefix) {
        var p = new Object[] {prefix + "%"};
        return new DeletePlan(List.of(new Step("ActiveMinute", " WHERE sessionId LIKE ?", p),
            new Step("UsageRow", " WHERE sessionId LIKE ?", p), new Step("SessionInfo", " WHERE sessionId LIKE ?", p)));
    }

    /** Every working directory whose path contains the text, case-insensitively. */
    public static DeletePlan projectContaining(String text) {
        var p = new Object[] {"%" + text.toLowerCase() + "%"};
        return new DeletePlan(List.of(
            new Step("ActiveMinute", " WHERE sessionId IN (SELECT sessionId FROM SessionInfo WHERE cwd.toLowerCase() LIKE ?)", p),
            new Step("UsageRow", " WHERE cwd.toLowerCase() LIKE ?", p), new Step("SessionInfo", " WHERE cwd.toLowerCase() LIKE ?", p)));
    }

    /** Exactly the working directories a report shows under this label (their last folder). */
    public static DeletePlan project(Database db, String label) {
        var cwds = new TreeSet<String>();
        for (var s : query(db, SessionInfo.TYPEDEF)) if (Labels.project(s.cwd()).equals(label) && s.cwd() != null) cwds.add(s.cwd());
        for (var r : query(db, UsageRow.TYPEDEF).isNotNull(UsageRow_A.$cwd)) if (Labels.project(r.cwd()).equals(label)) cwds.add(r.cwd());
        var p = new Object[] {new ArrayList<>(cwds)};
        return new DeletePlan(List.of(
            new Step("ActiveMinute", " WHERE sessionId IN (SELECT sessionId FROM SessionInfo WHERE cwd IN ?)", p),
            new Step("UsageRow", " WHERE cwd IN ?", p), new Step("SessionInfo", " WHERE cwd IN ?", p)));
    }

    public static DeletePlan before(LocalDate day, ZoneId zone) {
        var ms = day.atStartOfDay(zone).toInstant().toEpochMilli();
        return new DeletePlan(List.of(new Step("ActiveMinute", " WHERE minute < ?", new Object[] {ms / 60_000}),
            new Step("UsageRow", " WHERE ts < ?", new Object[] {ms}), new Step("SessionInfo", " WHERE lastTs < ?", new Object[] {ms})));
    }

    /** One line per kind of row, with how many would go. */
    public Map<String, Long> counts(Database db) {
        var out = new LinkedHashMap<String, Long>();
        for (var s : steps) {
            try (var rs = db.query("sql", "SELECT count(*) AS c FROM " + s.type() + s.where(), s.params())) {
                out.put(s.type(), rs.next().<Long>getProperty("c"));
            }
        }
        return out;
    }

    public void run(Database db) {
        db.transaction(() -> steps.forEach(s -> db.command("sql", "DELETE FROM " + s.type() + s.where(), s.params()).close()));
    }
}
