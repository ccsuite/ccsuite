package ccsuite.cclocalusagestats.report;

import ccsuite.cclocalusagestats.model.UsageRow;
import ccsuite.quotaweights.*;

/** Quota points (1 point = 1% of one account's 5-hour window) and list-price dollars of one message, from the shared table in quotaweights. */
public final class Quota {

    static final QuotaWeights WEIGHTS = QuotaWeights.standard();

    private Quota() {}

    public static Cost cost(UsageRow r) {
        var write5m = UsageRow.n(r.cacheWrite5m());
        var write1h = UsageRow.n(r.cacheWrite1h());
        var unsplit = Math.max(0, UsageRow.n(r.cacheWriteTotal()) - write5m - write1h);
        return WEIGHTS.cost(r.model(), UsageRow.n(r.inputTokens()), UsageRow.n(r.outputTokens()), UsageRow.n(r.cacheRead()), write5m + unsplit, write1h);
    }

    public static double points(UsageRow r) {
        return cost(r).points();
    }
}
