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
    /** Characters of request body per token (measured with the real tokenizer: 2.6 for code and prose, 2.0 for JSON). */
    double charsPerClaudeCodeToken = 2.6;
    /** Input tokens a completely unloaded free account takes in one five-hour window, uncached (measured 660,000 to 770,000; the middle). */
    double windowInputTokens = 680_000;
    /** The most one request can carry: the server refuses above it with "exceeds Claude's context limit" (measured 874,453 tokens, the same from four sizes). Also a hard 5,000,000 characters per prompt. */
    double contextLimitTokens = 874_453;
    /** One OUTPUT token costs this many input tokens (measured 4.0 to 4.6; the published ratio is 5). */
    double outputWeight = 5;
    /** The summary that closes a conversation (PRP status) is at most this share of the input, in output tokens. */
    double summaryShare = 0.2;
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
