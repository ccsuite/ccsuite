package ccsuite.cclocalusagestats.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

public final class Table {

    private Table() {}

    /** Prints rows as aligned text, or as JSON; every row is a map with the same keys, numbers right-aligned. */
    public static void print(List<Map<String, Object>> rows, boolean json) {
        if (json) {
            try {
                System.out.println(new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(rows));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return;
        }
        if (rows.isEmpty()) { System.out.println("(no rows)"); return; }
        var cols = new ArrayList<>(rows.get(0).keySet());
        var widths = cols.stream().mapToInt(c -> Math.max(c.length(), rows.stream().mapToInt(r -> cell(r.get(c)).length()).max().orElse(0))).toArray();
        line(cols, widths, rows.get(0), true);
        rows.forEach(r -> line(cols, widths, r, false));
    }

    static void line(List<String> cols, int[] w, Map<String, Object> row, boolean header) {
        var sb = new StringBuilder();
        for (var i = 0; i < cols.size(); i++) {
            var v = header ? cols.get(i) : cell(row.get(cols.get(i)));
            var left = row.get(cols.get(i)) instanceof String || header && !(row.get(cols.get(i)) instanceof Number);
            sb.append(left ? String.format("%-" + w[i] + "s", v) : String.format("%" + w[i] + "s", v)).append("  ");
        }
        System.out.println(sb.toString().stripTrailing());
    }

    static String cell(Object v) {
        return v instanceof Long || v instanceof Integer ? String.format("%,d", ((Number) v).longValue()) : v instanceof Double ? String.format("%,.1f", v) : String.valueOf(v);
    }
}
