package ccsuite.ccacc;

import java.util.Arrays;
import java.util.Set;

/**
 * What a {@code ccacc} command line asks for: the resident app, ccu's text view, the headless proxy,
 * the exe's own install, uninstall and update, the commands that change the running app (mode, part, account), or one of cswap's commands.
 */
enum Route {
    APP, TUI, SERVE, INSTALL, UNINSTALL, UPDATE, MIGRATE, CCU, CCLS, SWAP, CONTROL, GATE, FREE, CALLS, TOKENCHECK, STOPGATE, TRIM;

    static final Set<String> HELP = Set.of("-h", "--help", "help");

    /** The command words that are ccacc's own; the rest of the line after them goes to the route. */
    static final Set<String> WORDS = Set.of("app", "tui", "serve", "install", "uninstall", "update", "migrate", "ccu", "cswap", "ccls", "free", "calls", "tokencheck", "stop-gate", "trim");

    static Route of(String[] args) {
        if (args.length == 0) return APP;
        return switch (args[0]) {
            case "app" -> APP;
            case "tui" -> TUI;
            case "serve" -> SERVE;
            case "install" -> INSTALL;
            case "uninstall" -> UNINSTALL;
            case "update" -> UPDATE;
            case "migrate" -> MIGRATE;
            case "ccu" -> CCU;
            case "ccls" -> CCLS;
            case "cswap" -> SWAP;
            case "mode", "part", "account", "lanes", "window" -> CONTROL;
            case "gate", "ledger" -> GATE;
            case "free" -> FREE;
            case "calls" -> CALLS;
            case "tokencheck" -> TOKENCHECK;
            case "stop-gate" -> STOPGATE;
            case "trim" -> TRIM;
            default -> args[0].startsWith("--no-") ? APP : SWAP;
        };
    }

    /** The arguments after the command word, for the routes that have one. */
    static String[] rest(String[] args) {
        return args.length > 0 && WORDS.contains(args[0]) ? Arrays.copyOfRange(args, 1, args.length) : args;
    }
}
