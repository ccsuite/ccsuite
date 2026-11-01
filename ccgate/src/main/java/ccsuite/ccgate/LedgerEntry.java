package ccsuite.ccgate;

import com.fasterxml.jackson.annotation.*;
import datapotter.datahelper.Data;

/**
 * One line of the ledger: one attempt at one call. Sizes, counts and readings only, never content. Token fields carry
 * the names used by the Messages API, Claude Code's transcripts and ccusage, so the sources can be joined on
 * (message_id, request_id) without double counting.
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class LedgerEntry extends LedgerEntry_A {
    String t;
    String outcome;
    String reason;
    String grant;
    String key;
    String session;
    String agent;
    String project;
    String job;
    String remoteUser;
    String account;
    Integer attempt;
    String modelAsked;
    String model;
    String family;
    @JsonProperty("input_tokens") Long inputTokens;
    @JsonProperty("output_tokens") Long outputTokens;
    @JsonProperty("cache_creation_input_tokens") Long cacheCreationInputTokens;
    @JsonProperty("cache_read_input_tokens") Long cacheReadInputTokens;
    @JsonProperty("cache_creation_5m") Long cacheCreation5m;
    @JsonProperty("cache_creation_1h") Long cacheCreation1h;
    Double points;
    Double dollars;
    Integer status;
    String retryAfter;
    Long ms;
    Double u5h;
    Double u7d;
    String limitStatus;
    String claim;
    @JsonProperty("message_id") String messageId;
    @JsonProperty("request_id") String requestId;
    String stopReason;

    public LedgerEntry usage(Usage u) {
        return inputTokens(u.inputTokens()).outputTokens(u.outputTokens()).cacheCreationInputTokens(u.cacheCreationInputTokens())
                .cacheReadInputTokens(u.cacheReadInputTokens()).cacheCreation5m(u.cacheCreation5m()).cacheCreation1h(u.cacheCreation1h());
    }
}
