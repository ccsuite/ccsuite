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
                case "on", "off" -> onOff(sub.equals("on"));
                case "set" -> set(args);
                case "free-accounts" -> accounts(args);
                default -> Reply.error("lanes: show | open | drain | hold on|off | on | off | set <freeFiveLimit|freeWeekLimit|perAccountSlots|chatReuse|paidPin> <value> | free-accounts <id,id|all>");
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
        var ids = estimate.accounts().stream().sorted().toList();
        if (!ids.isEmpty()) {
            out.add("free accounts, room as the ledger left it (ccacc is not running, nothing live is known):");
            estimate.rooms(ids, now).forEach(r -> out.add("  " + FreeEstimator.line(r, now)));
        }
        return out;
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
        if (args.size() < 4) throw new IllegalArgumentException("lanes set <freeFiveLimit|freeWeekLimit|perAccountSlots|chatReuse|paidPin> <value>");
        var key = args.get(2);
        var value = args.get(3);
        Consumer<LaneSettings> apply = switch (key) {
            case "freeFiveLimit" -> l -> l.freeFiveLimit(number(value));
            case "freeWeekLimit" -> l -> l.freeWeekLimit(number(value));
            case "perAccountSlots" -> l -> l.perAccountSlots(number(value).intValue());
            case "paidPin" -> l -> l.paidPin(value.equalsIgnoreCase("off") ? "" : value);   // an account id, or off; read on every request, so it applies at once
            case "chatReuse" -> {
                if (!List.of("on", "off", "fresh", "true", "false").contains(value.toLowerCase())) throw new IllegalArgumentException("chatReuse takes off, on or fresh");
                yield l -> l.chatReuse(value.toLowerCase()).chatReuseExplicit(true);
            }
            default -> throw new IllegalArgumentException("unknown setting '" + key + "'");
        };
        change(apply);
        if (host != null && key.equals("chatReuse")) System.setProperty("ccapi.chatReuse", host.lanes().chatReuseMode());   // the proxy reads it on every call, so it applies at once
        return Reply.ok(key + " = " + value + (key.equals("perAccountSlots") ? " (applies when the lanes next start)" : ""));
    }

    Reply accounts(List<String> args) {
        if (args.size() < 3) throw new IllegalArgumentException("lanes free-accounts <id,id|all>");
        var ids = args.get(2).equals("all") ? List.<String>of() : Arrays.stream(args.get(2).split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        change(l -> l.freeAccounts(new ArrayList<>(ids)));
        return Reply.ok("free lane accounts: " + (ids.isEmpty() ? "every free account that is not monitor-only" : ids) + " (applies when the lanes next start)");
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
