package ccsuite.ccacc;

import module java.base;
import ccsuite.base.usage.Consolidated;

/**
 * All the free accounts as one, the counterpart of the paid widget's overall page: the average five-hour and weekly usage (every account counts the same),
 * how much of the five-hour window has gone on average and the rate (usage over time gone; 100% spends the window exactly as it passes), how many accounts
 * have room and about how many whole five-hour windows are left across the pool. A free account shows only the reset of its five-hour window, so the week
 * has no time or rate. A five-hour window that has reset since the reading counts as unused.
 */
record FreeOverall(int accounts, int withRoom, int fiveCount, int weekCount, double five, double fiveTime, double week, double fiveRate, double windowsLeft) {

    static final double FIVE_HOURS = 5 * 3600.0;

    static FreeOverall of(List<FreeGauges.Row> rows, double fiveLimit, double weekLimit, Instant now) {
        var data = new ArrayList<double[]>();
        var left = 0.0;
        for (var r : rows) {
            var reset = r.resetsAt() != null && r.resetsAt() <= now.getEpochSecond();
            var five = r.five() == null ? Double.NaN : reset ? 0 : r.five() * 100;
            var gone = r.resetsAt() == null || r.five() == null ? Double.NaN : reset ? 0 : Math.max(0, Math.min(100, (FIVE_HOURS - (r.resetsAt() - now.getEpochSecond())) / FIVE_HOURS * 100));
            data.add(new double[]{five, gone, r.seven() == null ? Double.NaN : r.seven() * 100, Double.NaN});
            if (r.five() != null || reset) left += reset ? 1 : Math.max(0, 1 - r.five());
        }
        var c = Consolidated.of(data);
        var week = data.stream().mapToDouble(d -> d[2]).filter(d -> !Double.isNaN(d)).average();
        var fiveOnly = data.stream().mapToDouble(d -> d[0]).filter(d -> !Double.isNaN(d)).average();
        var room = (int) rows.stream().filter(r -> FreeHeadroom.open(r, fiveLimit, weekLimit, now)).count();
        return new FreeOverall(rows.size(), room, (int) data.stream().filter(d -> !Double.isNaN(d[0])).count(), (int) data.stream().filter(d -> !Double.isNaN(d[2])).count(),
                fiveOnly.orElse(Double.NaN) / 100, c.fiveHourTime(), week.orElse(Double.NaN) / 100, c.fiveHourRate(), left);
    }

    Double fiveFraction() { return Double.isNaN(five) ? null : five; }

    Double weekFraction() { return Double.isNaN(week) ? null : week; }

    String text() {
        return accounts == 0 ? "no free accounts" : "Free overall: 5h " + pct(five) + " - week " + pct(week) + " - " + withRoom + " of " + accounts + " with room";
    }

    static String pct(double f) { return Double.isNaN(f) ? "-" : Math.round(f * 100) + "%"; }
}
