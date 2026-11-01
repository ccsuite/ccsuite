package ccsuite.cclocalusagestats.report;

import static datapotter.arcadedbhelper.Query.query;

import ccsuite.cclocalusagestats.model.UsageRow;
import com.arcadedb.database.Database;
import java.util.*;
import java.util.function.Function;

public final class Aggregator {

    private Aggregator() {}

    /** One streaming pass over the ledger, summing the rows that pass the filters under the key given. */
    public static Map<String, Totals> by(Database db, Filters f, Function<UsageRow, String> key) {
        var out = new TreeMap<String, Totals>();
        for (var r : query(db, UsageRow.TYPEDEF)) {
            if (f.accepts(r)) out.computeIfAbsent(key.apply(r), k -> new Totals()).add(r);
        }
        return out;
    }

    public static List<Map<String, Object>> rows(Map<String, Totals> m, int top, boolean newestFirstByKey) {
        var list = new ArrayList<>(m.entrySet());
        if (newestFirstByKey) Collections.reverse(list);
        else list.sort((a, b) -> Double.compare(b.getValue().points(), a.getValue().points()));
        var shown = top > 0 && list.size() > top ? list.subList(0, top) : list;
        return shown.stream().map(e -> e.getValue().toMap(e.getKey())).toList();
    }
}
