package ccsuite.cclocalusagestats.report;

import ccsuite.cclocalusagestats.model.UsageRow;

/**
 * Quota points of one message: 1 point = 1% of one account's 5-hour window.
 * Measured (PRP 62, 62-prp.03, one Team seat): tokens per 1% for fresh input Haiku 5.5 1,876,000, Sonnet 5.5 116,400, Opus 5.5 89,900;
 * output Sonnet 25,000, Opus 16,250; Sonnet cache reads 4,615,000.
 * Not measured, so assumed: Haiku output and every cache read at Sonnet's ratio to fresh input, cache writes at the list multiples 1.25 (5 min) and 2 (1 hour).
 * Other model versions use their family's numbers.
 */
public final class Quota {

    static final double SONNET_IN = 116_400, SONNET_OUT = 25_000, SONNET_CACHE_READ = 4_615_000;
    static final double OUT_OVER_IN = SONNET_IN / SONNET_OUT;
    static final double READ_DIVISOR = SONNET_CACHE_READ / SONNET_IN;

    private Quota() {}

    public static double points(UsageRow r) {
        var m = r.model() == null ? "" : r.model();
        var in = 1.0 / (m.contains("haiku") ? 1_876_000 : m.contains("sonnet") ? SONNET_IN : m.contains("opus") ? 89_900 : Double.POSITIVE_INFINITY);
        var out = m.contains("sonnet") ? 1 / SONNET_OUT : m.contains("opus") ? 1 / 16_250.0 : in * OUT_OVER_IN;
        var write5m = UsageRow.n(r.cacheWrite5m());
        var write1h = UsageRow.n(r.cacheWrite1h());
        var writeOther = Math.max(0, UsageRow.n(r.cacheWriteTotal()) - write5m - write1h);
        return UsageRow.n(r.inputTokens()) * in
            + UsageRow.n(r.outputTokens()) * out
            + (write5m + writeOther) * in * 1.25 + write1h * in * 2
            + UsageRow.n(r.cacheRead()) * in / READ_DIVISOR;
    }
}
