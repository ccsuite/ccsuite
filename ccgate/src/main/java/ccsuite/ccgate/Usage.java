package ccsuite.ccgate;

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
}
