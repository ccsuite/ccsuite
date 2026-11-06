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
    /** The measured cost of a first call on the free lane: about 2.2 points of a five-hour window per 10,000 characters sent (65,000 to 75,000 characters cost 14 to 16), plus the reply. */
    double pointsPer10kChars = 2.2;
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
