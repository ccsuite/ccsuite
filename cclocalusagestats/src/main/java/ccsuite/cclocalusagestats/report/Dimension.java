package ccsuite.cclocalusagestats.report;

import static ccsuite.cclocalusagestats.report.Labels.or;

import ccsuite.cclocalusagestats.model.UsageRow;
import com.arcadedb.database.Database;
import java.time.*;
import java.util.*;
import java.util.function.BiFunction;

/** The ways token usage can be grouped; the CLI verbs and the window's views are these. */
public enum Dimension {
    DAILY("daily", "Tokens per day, newest first.", true, (r, z) -> Instant.ofEpochMilli(r.ts()).atZone(z).toLocalDate().toString()),
    PROJECTS("projects", "Tokens per project (the last folder of the working directory).", false, (r, z) -> Labels.project(r.cwd())),
    MODELS("models", "Tokens per model.", false, (r, z) -> or(r.model(), "(unknown)")),
    ACCOUNTS("accounts", "Tokens per account (from the cswap switch log; blank before it began).", false, (r, z) -> or(r.account(), "(unknown)")),
    SESSIONS("sessions", "Tokens per session.", false, (r, z) -> id8(r.sessionId()) + "  " + Labels.project(r.cwd())),
    PRPS("prps", "Tokens per PRP (project and PRP number, from the session opener).", false,
        (r, z) -> Labels.project(r.cwd()) + (r.prp() == null ? "" : "  PRP " + r.prp()));

    public final String command;
    public final String description;
    final boolean newestFirst;
    final BiFunction<UsageRow, ZoneId, String> key;

    Dimension(String command, String description, boolean newestFirst, BiFunction<UsageRow, ZoneId, String> key) {
        this.command = command;
        this.description = description;
        this.newestFirst = newestFirst;
        this.key = key;
    }

    public List<Map<String, Object>> rows(Database db, Filters f) {
        var totals = Aggregator.by(db, f, r -> key.apply(r, f.zone()));
        return Aggregator.rows(totals, f.top(), newestFirst);
    }

    public static String id8(String sessionId) {
        var s = or(sessionId, "(none)");
        return s.substring(0, Math.min(8, s.length()));
    }
}
