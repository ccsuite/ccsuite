package ccsuite.cclocalusagestats.ingest;

import static datapotter.arcadedbhelper.Query.query;

import ccsuite.cclocalusagestats.model.*;
import com.arcadedb.database.Database;
import java.util.Arrays;

public final class AccountResolver {

    final long[] ts;
    final String[] to;

    public AccountResolver(Database db) {
        var events = query(db, SwitchEvent.TYPEDEF).orderByAsc(SwitchEvent_A.$ts).list();
        ts = events.stream().mapToLong(SwitchEvent::ts).toArray();
        to = events.stream().map(SwitchEvent::toAccount).toArray(String[]::new);
    }

    /** The account in use at that instant, or null before the first recorded switch. */
    public String at(long t) {
        var i = Arrays.binarySearch(ts, t);
        var idx = i >= 0 ? i : -i - 2;
        return idx < 0 ? null : to[idx];
    }
}
