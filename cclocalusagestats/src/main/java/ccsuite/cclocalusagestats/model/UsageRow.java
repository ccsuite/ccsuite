package ccsuite.cclocalusagestats.model;

import datapotter.arcadedbhelper.ArcadeData;
import datapotter.arcadedbhelper.TypeDef;

@ArcadeData
public final class UsageRow extends UsageRow_A {

    String key;
    String messageId;
    String requestId;
    String sessionId;
    String cwd;
    String model;
    String entrypoint;
    String version;
    String speed;
    String account;
    String prp;
    Long ts;
    Long inputTokens;
    Long outputTokens;
    Long thinkingTokens;
    Long cacheWrite5m;
    Long cacheWrite1h;
    Long cacheWriteTotal;
    Long cacheRead;
    Long webSearches;
    Boolean sidechain;

    public static final TypeDef<UsageRow> TYPEDEF =
        schemaBuilder()
            .factory(UsageRow::new)
            .unique($key)
            .lsmIndex($sessionId)
            .lsmIndex($ts)
            .__();

    public long total() {
        return n(inputTokens) + n(outputTokens) + n(cacheWriteTotal) + n(cacheRead);
    }

    public static long n(Long v) {
        return v == null ? 0 : v;
    }
}
