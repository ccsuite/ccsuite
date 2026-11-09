package ccsuite.ccacc;

import ccsuite.localserver.lanes.LaneKind;
import ccsuite.localserver.lanes.PortSpec;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.List;

/**
 * The two lanes of ccacc, each on its own loopback port and kept apart: {@code pclaude} is the paid accounts (every model), {@code fclaude} the free accounts
 * (haiku and sonnet). Nothing mixes the two, so a session and its prompt cache live in one place. Kept inside {@code settings.json}; every value is a setting.
 */
@Data
@Accessors(fluent = true, chain = true)
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY, getterVisibility = JsonAutoDetect.Visibility.NONE, isGetterVisibility = JsonAutoDetect.Visibility.NONE)
public class LaneSettings {

    static final List<String> NAMES = List.of("pclaude", "fclaude");

    /** The two ports; off until switched on. */
    boolean enabled = false;
    int pclaudePort = 8681, fclaudePort = 8686;
    /** A free account counts as used up at this fraction of its five-hour window, or of its week (for what the windows show). */
    double freeFiveLimit = 0.97, freeWeekLimit = 0.98;
    /**
     * The ping round (PRP 79): every {@code freePingEveryMinutes} the free accounts whose last reading (a probe, a served call or the ledger) is older than {@code freePingFreshMinutes},
     * or whose window has reset, get one small message so that their five-hour window is already running when they are needed. The recency limit sits a few minutes under the
     * interval on purpose: the account read last in one round took a few seconds after the first, so at exactly 60 and 60 it would be 59 minutes old at the next round and wait for the one after.
     * {@code freePingSpreadSeconds} is the pause between two pings of a round.
     */
    int freePingEveryMinutes = 60, freePingFreshMinutes = 55, freePingSpreadSeconds = 5;
    /** Requests in flight on one free account at once (two is safe, four at once get HTTP 429). */
    int perAccountSlots = 2;
    /** The guard against starting a conversation the free lane cannot carry: a request bigger than this many characters that starts a new chat is refused when the free account with the most room cannot take it. */
    int guardMinChars = 40_000;
    /**
     * The cost of a Sonnet 5.5 call on the free lane, MEASURED on 2026-11-07 (night, off-peak?) with exact token counts: input costs about 1 point per 6,800 tokens (6,600 for Java source,
     * 6,800 for JSON, 7,700 for prose: a window of 100 points takes 660,000 to 770,000 input tokens), output about 1 point per 1,670 tokens (4.0 to 4.6 times the input). In characters
     * (2.6 per token for code and prose) that is about 0.6 points per 10,000 characters of prompt. The midday calls of 2026-11-06 cost 3 times more per character (cause unknown,
     * possibly peak hours): the estimator learns the real rate from the ledger, so a seed that is off corrects itself.
     */
    double pointsPer10kChars = 0.6;
    /** Points a new chat costs before its prompt (measured about nothing). */
    double firstCallPoints = 0.3;
    /** Points a reply is expected to cost (about 1,000 output tokens). */
    double expectedReplyPoints = 0.7;
    /** Haiku costs about this fraction of Sonnet 5.5 (NOT measured on 2026-11-07; the midday calls of 2026-11-06 suggested a third). */
    double haikuFactor = 0.3;
    /** PRP 88: the model that predicts a call's cost: "rate" (PRP 78, one rate per 10,000 characters) or "terms" (fresh input, cache read, cold rewrite, output). The other one runs in the shadow and is written to the ledger beside it. Applies at once. */
    String costModel = "rate";
    /**
     * PRP 88, the terms model for Sonnet 5.5 (Haiku is {@link #haikuFactor} of it), MEASURED 2026-11-09 about 04:40 UTC with calls pinned to seven accounts: per call 0.1 point; fresh prompt
     * 0.6 per 10,000 characters (50K to 400K, linear, a new chat costs nothing more); a chat's held conversation 0.015 per 10,000 characters per call while warm (the cache, about 3% of fresh);
     * output 2.1 per 10,000 reply characters (3.5 times fresh). Held longer than {@code termsWarmSeconds} without a call, a chat is priced cold at {@code termsColdPer10k}. The reply expected
     * before a chat has replied is {@code termsReplyChars}. A scale per model family and a factor per chat are learned from the gauge on top of these.
     */
    double termsPerCall = 0.1, termsFreshPer10k = 0.6, termsReadPer10k = 0.015, termsColdPer10k = 0.6, termsReplyPer10k = 2.1, termsWarmSeconds = 3600, termsReplyChars = 3000;
    /** Input tokens a completely unloaded free account takes in one five-hour window, uncached (measured 660,000 to 770,000; the middle). Characters become tokens only through the proxy's one ratio, {@code TokenEstimate.CHARS_PER_TOKEN} (PRP 84): there is no second copy here. */
    double windowInputTokens = 680_000;
    /** The SAFE START (PRP 84): a new conversation may start while its estimated input is at most this many tokens (half the window; the rest is kept to summarise). Above it the guard refuses. */
    double safeStartTokens = 340_000;
    /** The WORK ZONE (PRP 84): a conversation is worked on up to this many tokens; above it the cycle is closed (PRP status, new conversation, or compact). Claude Code is told to compact here. */
    double workZoneTokens = 170_000;
    /** PRP 86, the free lane's zones in input tokens (accepted by the user 2026-11-08): warn and close for an account with all its room; hard is {@link #safeStartTokens}; both scale down with the room. */
    double freeWarnTokens = 200_000, freeCloseTokens = 250_000;
    /** PRP 86: what a conversation costs just to start (system prompt, tools, the CLAUDE.md chain; measured about 100,000) and the least working room kept above it before a close. */
    double startCostTokens = 100_000, workMarginTokens = 30_000;
    /** PRP 86, the paid profile: close in 200K to 300K, extended toward 500K while the prompt cache is warm. Nothing in the paid lane is touched; these are only read by {@code lanes zones}. */
    double paidWarnTokens = 200_000, paidCloseTokens = 300_000, paidHardTokens = 500_000;
    /** The most one request can carry: the server refuses above it with "exceeds Claude's context limit" (measured 874,453 tokens, the same from four sizes). Also a hard 5,000,000 characters per prompt. */
    double contextLimitTokens = 874_453;
    /** Points of room that must stay over after the call. */
    double guardMarginPoints = 5;
    /** Chat reuse of the free lane (PRP 74): "on" (one web chat per Claude Code session, only the new part sent: about a third of the gauge cost), "off", or "fresh" (a new chat every call, the control for a measurement). On unless the user chose otherwise: see {@link #chatReuseExplicit}. */
    String chatReuse = "on";
    /** True once the user chose a value with {@code ccacc lanes set chatReuse}. Until then the default (on) applies whatever an older settings file holds (it only held the former default, off). */
    boolean chatReuseExplicit = false;

    /** The setting as the proxy reads it: on, off or fresh (an older settings file may hold true or false). */
    public String chatReuseMode() {
        if (!chatReuseExplicit) return "on";
        var v = chatReuse == null ? "on" : chatReuse.trim().toLowerCase();
        return v.equals("fresh") ? "fresh" : v.equals("off") || v.equals("false") ? "off" : "on";
    }

    /** The one paid account the paid lane may use, or empty for the usual ranking. While set the lane never moves to another account and refuses when this one cannot serve, so a measured run is visibly on it. Applies at once. */
    String paidPin = "";

    /** The pinned paid account, or null when off. */
    public String paidPinOrNull() { return paidPin == null || paidPin.isBlank() || paidPin.equalsIgnoreCase("off") ? null : paidPin.trim(); }

    /** PRP 87: the request log of the free lane (what Claude Code sent, incremental jsonl, private text). Off unless switched on; applies at the next start of the lanes; days kept before removal. */
    boolean bodyLog = false;
    int bodyLogDays = 14;

    /** The free accounts the free lane may use, by id; empty means every free account that is not monitor-only. */
    List<String> freeAccounts = new ArrayList<>();

    public int port(String name) {
        return switch (name) { case "pclaude" -> pclaudePort; case "fclaude" -> fclaudePort; default -> throw unknown(name); };
    }

    public List<PortSpec> specs() {
        return List.of(new PortSpec("pclaude", pclaudePort, LaneKind.PAID), new PortSpec("fclaude", fclaudePort, LaneKind.FREE));
    }

    static IllegalArgumentException unknown(String name) {
        return new IllegalArgumentException("unknown port '" + name + "': " + String.join(", ", NAMES));
    }
}
