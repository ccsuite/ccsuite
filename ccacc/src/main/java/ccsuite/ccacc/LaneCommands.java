package ccsuite.ccacc;

import module java.base;
import ccsuite.ccacc.Control.Reply;

/**
 * {@code ccacc lanes}: show the two lanes (pclaude, paid; fclaude, free), switch them on or off, and set their settings. Inside the running app
 * ({@code host} set) a change acts at once where it can; run alone it is written to the settings for the next start. The watcher's own
 * {@code ccacc mode auto|locked|paused} is not a lane command.
 */
final class LaneCommands {

    final CcaccSettingsStore store;
    /** The running app's lanes, or null when run alone. */
    final LaneHost host;
    /** Where the free lane's calls are kept; the status is read back from it when no app runs. */
    final FreeLedger ledger;

    LaneCommands(CcaccSettingsStore store, LaneHost host) { this(store, host, FreeLedger.standard()); }

    LaneCommands(CcaccSettingsStore store, LaneHost host, FreeLedger ledger) { this.store = store; this.host = host; this.ledger = ledger; }

    static boolean handles(List<String> a) { return !a.isEmpty() && a.get(0).equals("lanes"); }

    Reply run(List<String> args) {
        try {
            var sub = args.size() > 1 ? args.get(1) : "show";
            return switch (sub) {
                case "show", "status" -> Reply.ok(String.join("\n", lines()));   // show, status, open and drain only read
                case "open" -> Reply.ok(host == null ? "ccacc is not running: no calls in flight" : String.join("\n", host.openLines()));
                case "drain" -> Reply.ok(host == null ? "idle (ccacc is not running)" : host.drain(Duration.ofMinutes(5)));
                case "hold" -> hold(args);
                case "limits" -> Reply.ok(String.join("\n", (host != null && host.free != null ? host.free.estimator : FreeEstimator.fromLedger(ledger, store.load().lanes(), java.time.Instant.now())).limitLines()));
                case "zones" -> zones(args);   // read-only (PRP 86)
                case "on", "off" -> onOff(sub.equals("on"));
                case "set" -> set(args);
                case "free-accounts" -> accounts(args);
                default -> Reply.error("lanes: show | limits | zones [--room N] | open | drain | hold on|off | on | off | set <freeFiveLimit|freeWeekLimit|perAccountSlots|freePingEveryMinutes|freePingFreshMinutes|freePingSpreadSeconds|pclaudePort|fclaudePort|chatReuse|paidPin|bodyLog|bodyLogDays|costModel|termsPerCall|termsFreshPer10k|termsReadPer10k|termsColdPer10k|termsReplyPer10k|termsWarmSeconds|termsReplyChars|rateProbe|rateProbeEveryMinutes|rateProbeAccounts|rateProbeChars|rateProbeMinRoom|rateProbeMaxAgeMinutes> <value> | free-accounts <id,id|all>");
            };
        } catch (IllegalArgumentException e) {
            return Reply.error(e.getMessage());
        }
    }

    List<String> lines() {
        if (host != null) return host.report();
        var l = store.load().lanes();
        var out = new ArrayList<>(List.of("lanes: " + (l.enabled() ? "on" : "off") + " in the settings (ccacc is not running)",
                String.format("  pclaude   127.0.0.1:%-5d the paid accounts, every model", l.pclaudePort()),
                String.format("  fclaude   127.0.0.1:%-5d the free accounts, haiku and sonnet", l.fclaudePort())));
        var now = java.time.Instant.now();
        var estimate = FreeEstimator.fromLedger(ledger, l, now);   // read-only: the room each free account had when the ledger was last written
        out.add(estimate.limitText());
        var ids = estimate.accounts().stream().sorted().toList();
        if (!ids.isEmpty()) {
            out.add("free accounts, room as the ledger left it (ccacc is not running, nothing live is known):");
            estimate.rooms(ids, now).forEach(r -> out.add("  " + estimate.line(r, now)));
        }
        return out;
    }

    /**
     * {@code lanes zones [--room N]}: the warn, close and hard points of a cycle in input tokens, for the free lane (scaled by the room: N points of the window, or the best
     * predicted room among the free accounts, 100 when none was ever read) and for the paid profile. Read-only: nothing is written.
     */
    Reply zones(List<String> args) {
        var l = (host != null ? host.lanes() : store.load().lanes());
        double room;
        if (args.size() > 2) {
            if (!args.get(2).equals("--room") || args.size() < 4) throw new IllegalArgumentException("lanes zones [--room N]");
            room = number(args.get(3));
        } else {
            var now = java.time.Instant.now();
            var estimate = host != null && host.free != null ? host.free.estimator : FreeEstimator.fromLedger(ledger, l, now);
            var best = estimate.bestRoom(estimate.accounts(), now);
            room = best < 0 ? 100 : best;
        }
        var out = new ArrayList<>(Zones.free(l, room).lines("free"));
        out.addAll(Zones.paid(l).lines("paid"));
        return Reply.ok(String.join("\n", out));
    }

    Reply hold(List<String> args) {
        if (host == null) throw new IllegalArgumentException("hold: ccacc is not running, there is no lane to hold");
        if (args.size() < 3 || !List.of("on", "off").contains(args.get(2))) throw new IllegalArgumentException("lanes hold on|off");
        host.hold(args.get(2).equals("on"));
        return Reply.ok("free lane hold " + args.get(2) + (args.get(2).equals("on") ? ": new requests get 503 until it is turned off or the app restarts" : ""));
    }

    Reply onOff(boolean on) {
        if (host != null) host.enable(on);
        else change(l -> l.enabled(on));
        return Reply.ok("lanes " + (on ? "on" : "off") + (host == null ? " in the settings; ccacc is not running, it applies at the next start" : ""));
    }

    Reply set(List<String> args) {
        if (args.size() < 4) throw new IllegalArgumentException("lanes set <freeFiveLimit|freeWeekLimit|perAccountSlots|freePingEveryMinutes|freePingFreshMinutes|freePingSpreadSeconds|pclaudePort|fclaudePort|chatReuse|paidPin|bodyLog|bodyLogDays|costModel|termsPerCall|termsFreshPer10k|termsReadPer10k|termsColdPer10k|termsReplyPer10k|termsWarmSeconds|termsReplyChars|rateProbe|rateProbeEveryMinutes|rateProbeAccounts|rateProbeChars|rateProbeMinRoom|rateProbeMaxAgeMinutes> <value>");
        var key = args.get(2);
        var value = args.get(3);
        Consumer<LaneSettings> apply = switch (key) {
            case "freeFiveLimit" -> l -> l.freeFiveLimit(fraction(key, value));
            case "freeWeekLimit" -> l -> l.freeWeekLimit(fraction(key, value));
            case "perAccountSlots" -> l -> l.perAccountSlots(atLeast(key, value, 1));
            case "freePingEveryMinutes" -> l -> l.freePingEveryMinutes(atLeast(key, value, 1));   // read again after every round
            case "freePingFreshMinutes" -> l -> l.freePingFreshMinutes(atLeast(key, value, 1));
            case "freePingSpreadSeconds" -> l -> l.freePingSpreadSeconds(atLeast(key, value, 0));
            case "pclaudePort" -> l -> { var p = port(key, value); if (p == l.fclaudePort()) throw new IllegalArgumentException("pclaudePort cannot be the same port as fclaudePort (" + p + ")"); l.pclaudePort(p); };
            case "fclaudePort" -> l -> { var p = port(key, value); if (p == l.pclaudePort()) throw new IllegalArgumentException("fclaudePort cannot be the same port as pclaudePort (" + p + ")"); l.fclaudePort(p); };
            case "paidPin" -> l -> l.paidPin(value.equalsIgnoreCase("off") ? "" : value);   // an account id, or off; read on every request, so it applies at once
            case "chatReuse" -> {
                if (!List.of("on", "off", "fresh", "true", "false").contains(value.toLowerCase())) throw new IllegalArgumentException("chatReuse takes off, on or fresh");
                yield l -> l.chatReuse(value.toLowerCase()).chatReuseExplicit(true);
            }
            case "bodyLog" -> {
                if (!List.of("on", "off", "true", "false").contains(value.toLowerCase())) throw new IllegalArgumentException("bodyLog takes on or off");
                yield l -> l.bodyLog(value.equalsIgnoreCase("on") || value.equalsIgnoreCase("true"));   // PRP 87: the request log; applies when the lanes next start
            }
            case "bodyLogDays" -> l -> l.bodyLogDays(atLeast(key, value, 1));
            case "costModel" -> {
                if (!List.of("rate", "terms").contains(value.toLowerCase())) throw new IllegalArgumentException("costModel takes rate or terms");
                yield l -> l.costModel(value.toLowerCase());   // PRP 88: read on every prediction, so it applies at once
            }
            case "termsPerCall" -> l -> l.termsPerCall(atLeast(key, value, 0.0));
            case "termsFreshPer10k" -> l -> l.termsFreshPer10k(atLeast(key, value, 0.0));
            case "termsReadPer10k" -> l -> l.termsReadPer10k(atLeast(key, value, 0.0));
            case "termsColdPer10k" -> l -> l.termsColdPer10k(atLeast(key, value, 0.0));
            case "termsReplyPer10k" -> l -> l.termsReplyPer10k(atLeast(key, value, 0.0));
            case "termsWarmSeconds" -> l -> l.termsWarmSeconds(atLeast(key, value, 0.0));
            case "termsReplyChars" -> l -> l.termsReplyChars(atLeast(key, value, 0.0));
            case "rateProbe" -> {
                if (!List.of("on", "off", "true", "false").contains(value.toLowerCase())) throw new IllegalArgumentException("rateProbe takes on or off");
                yield l -> l.rateProbe(value.equalsIgnoreCase("on") || value.equalsIgnoreCase("true"));   // PRP 88: off = the factor goes back to 1 at once
            }
            case "rateProbeEveryMinutes" -> l -> l.rateProbeEveryMinutes(atLeast(key, value, 10));
            case "rateProbeAccounts" -> l -> l.rateProbeAccounts(atLeast(key, value, 1));
            case "rateProbeChars" -> l -> l.rateProbeChars(atLeast(key, value, 20_000));
            case "rateProbeMinRoom" -> l -> l.rateProbeMinRoom(atLeast(key, value, 0));
            case "rateProbeMaxAgeMinutes" -> l -> l.rateProbeMaxAgeMinutes(atLeast(key, value, 10));
            default -> throw new IllegalArgumentException("unknown setting '" + key + "'");
        };
        change(apply);
        if (host != null && key.equals("chatReuse")) System.setProperty("ccapi.chatReuse", host.lanes().chatReuseMode());   // the proxy reads it on every call, so it applies at once
        return Reply.ok(key + " = " + value + (List.of("perAccountSlots", "pclaudePort", "fclaudePort", "bodyLog", "bodyLogDays").contains(key) ? " (applies when the lanes next start)" : ""));
    }

    Reply accounts(List<String> args) {
        if (args.size() < 3) throw new IllegalArgumentException("lanes free-accounts <id,id|all>");
        var ids = args.get(2).equals("all") ? List.<String>of() : Arrays.stream(args.get(2).split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        change(l -> l.freeAccounts(new ArrayList<>(ids)));
        return Reply.ok("free lane accounts: " + (ids.isEmpty() ? "every free account that is not monitor-only" : ids) + " (applies when the lanes next start)");
    }

    /** A limit is a fraction of the window, above 0 and at most 1 (0.97 is 97%). */
    static double fraction(String key, String v) {
        var n = number(v);
        if (n <= 0 || n > 1) throw new IllegalArgumentException(key + " is a fraction above 0 and at most 1 (0.97 is 97%), not " + v);
        return n;
    }

    static double atLeast(String key, String v, double min) {
        var n = number(v);
        if (n < min) throw new IllegalArgumentException(key + " is a number of " + min + " or more, not " + v);
        return n;
    }

    static int atLeast(String key, String v, int min) {
        var n = number(v);
        if (n != Math.rint(n) || n < min) throw new IllegalArgumentException(key + " is a whole number of " + min + " or more, not " + v);
        return n.intValue();
    }

    static int port(String key, String v) {
        var n = number(v);
        if (n != Math.rint(n) || n < 1024 || n > 65535) throw new IllegalArgumentException(key + " is a port between 1024 and 65535, not " + v);
        return n.intValue();
    }

    static Double number(String s) {
        try { return Double.valueOf(s); } catch (NumberFormatException e) { throw new IllegalArgumentException("not a number: " + s); }
    }

    /** Applies a change to the live settings (and saves them), or to the settings file when no app runs. */
    void change(Consumer<LaneSettings> apply) {
        if (host != null) { apply.accept(host.lanes()); host.store.save(host.settings); }
        else { var s = store.load(); apply.accept(s.lanes()); store.save(s); }
    }
}
