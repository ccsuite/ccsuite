package ccsuite.cclocalusagestats.ui;

import ccsuite.cclocalusagestats.report.*;
import com.arcadedb.database.Database;
import java.util.*;
import java.util.function.BiFunction;

enum View {
    DAILY("Daily", Dimension.DAILY::rows),
    PROJECTS("Projects", Dimension.PROJECTS::rows),
    SESSIONS("Sessions", Dimension.SESSIONS::rows),
    PRPS("PRPs", Dimension.PRPS::rows),
    MODELS("Models", Dimension.MODELS::rows),
    ACCOUNTS("Accounts", Dimension.ACCOUNTS::rows),
    SHARE("Share", (db, f) -> ShareReport.run(db, f, false)),
    HOURS("Time by hour", (db, f) -> ActivityReport.by(db, f, "hour")),
    WORK("Time by project", (db, f) -> ActivityReport.by(db, f, "project"));

    final String title;
    final BiFunction<Database, Filters, List<Map<String, Object>>> load;

    View(String title, BiFunction<Database, Filters, List<Map<String, Object>>> load) {
        this.title = title;
        this.load = load;
    }
}
