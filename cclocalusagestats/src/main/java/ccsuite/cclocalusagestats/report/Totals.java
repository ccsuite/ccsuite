package ccsuite.cclocalusagestats.report;

import ccsuite.cclocalusagestats.model.UsageRow;
import datapotter.datahelper.Data;
import java.util.*;

@Data
public final class Totals extends Totals_A {
    long messages;
    long input;
    long output;
    long cacheWrite;
    long cacheRead;
    double points;
    double assumedPoints;
    double dollars;

    public Totals add(UsageRow r) {
        messages++;
        input += UsageRow.n(r.inputTokens());
        output += UsageRow.n(r.outputTokens());
        cacheWrite += UsageRow.n(r.cacheWriteTotal());
        cacheRead += UsageRow.n(r.cacheRead());
        var c = Quota.cost(r);
        points += c.points();
        assumedPoints += c.assumedPoints();
        dollars += c.dollars();
        return this;
    }

    public double points() {
        return points;
    }

    public Map<String, Object> toMap(String key) {
        var m = new LinkedHashMap<String, Object>();
        m.put("key", key);
        m.put("messages", messages);
        m.put("points", Math.round(points * 10) / 10.0);
        m.put("assumed", points == 0 ? "0%" : Math.round(100 * assumedPoints / points) + "%");
        m.put("dollars", Math.round(dollars * 100) / 100.0);
        m.put("input", input);
        m.put("output", output);
        m.put("cacheWrite", cacheWrite);
        m.put("cacheRead", cacheRead);
        return m;
    }
}
