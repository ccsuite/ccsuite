package ccsuite.ccacc;

import module java.base;

/** The free accounts' usage as text and as JSON for the command line: the overall first, then one line per account. */
final class FreeReport {

    static List<String> text(List<FreeGauges.Row> rows, boolean overallOnly, LaneSettings limits, Instant now) {
        var out = new ArrayList<String>();
        if (rows.isEmpty()) { out.add("No free account has been read yet."); return out; }
        var o = FreeOverall.of(rows, limits.freeFiveLimit(), limits.freeWeekLimit(), now);
        out.add(String.format("ALL   5h %s %s   week %s %s   %d of %d with room, about %.1f five-hour windows left", FreeGauges.bar(o.fiveFraction()), FreeGauges.percent(o.fiveFraction()),
                FreeGauges.bar(o.weekFraction()), FreeGauges.percent(o.weekFraction()), o.withRoom(), o.accounts(), o.windowsLeft()));
        if (o.fiveTime() > 0) out.add(String.format("      five-hour windows are %d%% gone on average, so the pool spends them at %d%% of the pace that would just use them up", Math.round(o.fiveTime()), Math.round(o.fiveRate())));
        if (!overallOnly) rows.forEach(r -> out.add("  " + FreeGauges.line(r, now)));
        return out;
    }

    static String json(List<FreeGauges.Row> rows, LaneSettings limits, Instant now) {
        var o = FreeOverall.of(rows, limits.freeFiveLimit(), limits.freeWeekLimit(), now);
        var root = FreeGauges.JSON.createObjectNode();
        var all = root.putObject("overall");
        all.put("accounts", o.accounts()).put("withRoom", o.withRoom()).put("fiveHour", o.fiveFraction()).put("week", o.weekFraction())
                .put("fiveHourTimeGonePct", Double.isNaN(o.fiveTime()) ? null : o.fiveTime()).put("fiveHourRatePct", o.fiveTime() > 0 ? o.fiveRate() : null).put("fiveHourWindowsLeft", o.windowsLeft());
        var list = root.putArray("accounts");
        rows.forEach(r -> list.addObject().put("id", r.id()).put("fiveHour", r.five()).put("week", r.seven()).put("status", r.status()).put("resetsAt", r.resetsAt())
                .put("read", r.read().toString()).put("model", r.model()).put("problem", r.problem()));
        return root.toPrettyString();
    }

    private FreeReport() {}
}
