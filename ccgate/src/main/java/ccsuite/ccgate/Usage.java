package ccsuite.ccgate;

import ccsuite.quotaweights.Cost;
import ccsuite.quotaweights.QuotaWeights;
import datapotter.datahelper.Data;

/** The token counters of one answer, named as in the Messages API, Claude Code's transcripts and ccusage. */
@Data
public final class Usage extends Usage_A {
    long inputTokens;
    long outputTokens;
    long cacheCreationInputTokens;
    long cacheReadInputTokens;
    long cacheCreation5m;
    long cacheCreation1h;

    /** What this answer weighs against the quota; a cache write not split into 5 minutes and 1 hour counts as 5 minutes. */
    public Cost cost(QuotaWeights weights, String model) {
        var write5m = cacheCreation5m + cacheCreation1h == 0 ? cacheCreationInputTokens : cacheCreation5m;
        return weights.cost(model, inputTokens, outputTokens, cacheReadInputTokens, write5m, cacheCreation1h);
    }
}
