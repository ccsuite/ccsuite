package ccsuite.ccacc;

import ccsuite.base.config.AccountEditor;
import ccsuite.oauthswap.SwapService;
import ccsuite.oauthswap.SwitchResult;
import ccsuite.oauthswap.watch.WatchMode;
import javafx.application.Platform;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import ccsuite.ccacc.Control.Reply;

/**
 * The commands that change state: {@code switch}, {@code register}, {@code mode}, {@code part} and {@code account}.
 * Inside the running app ({@code parts} set) they act on the live parts, so the app stays the one writer and the tray,
 * the card and the window follow at once. Run alone ({@code parts} null: no app is running) only the ones that
 * need no running part work: the settings and the accounts file are changed on disk for the next start.
 */
final class Commands {

    final Accounts accounts;
    final CcaccSettingsStore settingsStore;
    /** The running app's parts, or null when run alone. */
    final Parts parts;
    final SwapService swap;

    Commands(Accounts accounts, CcaccSettingsStore settingsStore, Parts parts, SwapService swap) {
        this.accounts = accounts; this.settingsStore = settingsStore; this.parts = parts; this.swap = swap;
    }

    Reply run(List<String> args) {
        try {
            return switch (args.get(0)) {
                case "switch" -> doSwitch(args.size() > 1 ? args.get(1) : null);
                case "register" -> doRegister(args);
                case "mode" -> mode(args);
                case "part" -> part(args);
                case "account" -> account(args);
                default -> Reply.error("unknown command " + args.get(0));
            };
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Reply.error(e.getMessage());
        } catch (Exception e) {
            return Reply.error(e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    // ---- switch and register: only the running app does these (a command line alone uses cswap's own commands)

    Reply doSwitch(String id) throws Exception {
        if (parts == null) throw new IllegalStateException("ccacc is not running");
        SwitchResult r;
        var watcher = parts.watcher;
        if (watcher != null) r = watcher.switchNow(id);
        else {
            r = id == null ? swap.switchNext() : swap.switchTo(id);
            parts.bridge.onSwitched(r, false);
        }
        return Reply.ok(r.changed()
                ? "Switched " + (r.fromId() == null ? "(unmanaged)" : r.fromId()) + " -> " + r.toId() + " (" + r.toEmail()
                        + "). Claude Code will pick it up on your next message."
                : "Already on " + r.toId() + " (" + r.toEmail() + ").");
    }

    Reply doRegister(List<String> args) throws Exception {
        if (parts == null) throw new IllegalStateException("ccacc is not running");
        String id = null;
        for (var i = 1; i < args.size(); i++) if (args.get(i).equals("--id") && i + 1 < args.size()) id = args.get(++i);
        var cred = swap.register(Optional.ofNullable(id));
        return Reply.ok("Registered " + cred.id() + " (" + cred.email() + ")");
    }

    // ---- mode and parts

    Reply mode(List<String> args) {
        if (args.size() != 2) throw new IllegalArgumentException("usage: ccacc mode auto|locked|paused");
        var m = WatchMode.valueOf(args.get(1).toUpperCase(Locale.ROOT));
        if (parts != null) fx(() -> { parts.mode(m); return null; });
        else {
            var s = settingsStore.load();
            settingsStore.save(s.watchMode(m));
        }
        return Reply.ok("Watcher mode: " + m.name().toLowerCase(Locale.ROOT) + (parts == null ? " (ccacc is not running: saved for its next start)" : ""));
    }

    Reply part(List<String> args) {
        if (args.size() != 3) throw new IllegalArgumentException("usage: ccacc part watch|widget|server on|off");
        var name = args.get(1).toLowerCase(Locale.ROOT);
        var on = switch (args.get(2).toLowerCase(Locale.ROOT)) {
            case "on", "true", "yes" -> true;
            case "off", "false", "no" -> false;
            default -> throw new IllegalArgumentException("on or off, not " + args.get(2));
        };
        if (!List.of("watch", "widget", "server").contains(name)) throw new IllegalArgumentException("the parts are watch, widget and server, not " + name);
        if (parts != null) {
            fx(() -> {
                switch (name) { case "watch" -> parts.watch(on); case "widget" -> parts.widget(on); default -> parts.server(on); }
                return null;
            });
        } else {
            var s = settingsStore.load();
            switch (name) { case "watch" -> s.watch(on); case "widget" -> s.widget(on); default -> s.server(on); }
            settingsStore.save(s);
        }
        return Reply.ok(name + " " + (on ? "on" : "off") + (parts == null ? " (ccacc is not running: saved for its next start)" : ""));
    }

    // ---- accounts

    static final String ACCOUNT_USAGE = """
            usage: ccacc account list
                   ccacc account add <id> name=<name> email=<email> [property=value ...]
                   ccacc account set <id> property=value [property=value ...]
                   ccacc account remove <id>
            properties: %s""".formatted(String.join(", ", AccountEditor.PROPERTIES));

    Reply account(List<String> args) throws java.io.IOException {
        if (args.size() < 2) throw new IllegalArgumentException(ACCOUNT_USAGE);
        switch (args.get(1)) {
            case "list" -> {
                var sb = new StringBuilder();
                for (var c : accounts.list()) sb.append(AccountEditor.describe(c)).append('\n');
                return Reply.ok(sb.toString().stripTrailing());
            }
            case "add" -> {
                if (args.size() < 3) throw new IllegalArgumentException(ACCOUNT_USAGE);
                var props = pairs(args, 3);
                props.put("id", args.get(2));
                accounts.add(props);
                return Reply.ok("Added " + args.get(2) + ". Log in as it in Claude Code, then run: ccacc register --id " + args.get(2));
            }
            case "set" -> {
                if (args.size() < 4) throw new IllegalArgumentException(ACCOUNT_USAGE);
                accounts.set(args.get(2), pairs(args, 3));
                return Reply.ok("Changed " + args.get(2));
            }
            case "remove" -> {
                if (args.size() != 3) throw new IllegalArgumentException(ACCOUNT_USAGE);
                accounts.remove(args.get(2));
                return Reply.ok("Removed " + args.get(2) + " (its saved login stays on disk)");
            }
            default -> throw new IllegalArgumentException(ACCOUNT_USAGE);
        }
    }

    static LinkedHashMap<String, String> pairs(List<String> args, int from) {
        var map = new LinkedHashMap<String, String>();
        for (var a : args.subList(from, args.size())) {
            var eq = a.indexOf('=');
            if (eq <= 0) throw new IllegalArgumentException("expected property=value, not " + a);
            map.put(a.substring(0, eq), a.substring(eq + 1));
        }
        return map;
    }

    /** Parts that own windows run on the JavaFX thread; the caller waits for them. */
    static <T> T fx(Supplier<T> work) {
        if (Platform.isFxApplicationThread()) return work.get();
        var done = new CompletableFuture<T>();
        Platform.runLater(() -> { try { done.complete(work.get()); } catch (Throwable t) { done.completeExceptionally(t); } });
        try {
            return done.get(30, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            throw e.getCause() instanceof RuntimeException r ? r : new IllegalStateException(e.getCause());
        } catch (Exception e) {
            throw new IllegalStateException("the app did not finish in time: " + e);
        }
    }
}
