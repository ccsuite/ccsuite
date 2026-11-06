package ccsuite.ccacc;

import ccsuite.localserver.cli.CcLocalServerCli;
import ccsuite.oauthswap.cli.ClaudeSwapCli;
import ccsuite.usagetracker.UsageTrackerLauncher;
import javafx.application.Application;
import picocli.CommandLine;

import java.util.stream.Stream;

/**
 * ccacc, the Claude Code accounts tool: ccu, cswap and ccls in one program.
 * <pre>
 * ccacc                     the resident app: tray icon and menu, account watcher, usage widget, local proxy
 * ccacc app --no-server     the same, leaving a part out for this run (--no-watch --no-widget --no-server)
 * ccacc list | status | usage | switch | register | watch    cswap's commands
 * ccacc tui                 ccu's usage view in the terminal
 * ccacc serve               the local proxy alone, no tray
 * </pre>
 * This class is not a JavaFX Application, so the shaded jar finds its JavaFX modules when the app starts.
 */
public final class CcaccMain {

    public static void main(String[] args) {
        if (args.length > 0 && Route.HELP.contains(args[0])) System.out.println(overview());
        var rest = Route.rest("help".equals(args.length > 0 ? args[0] : "") ? new String[]{"--help"} : args);
        switch (Route.of(args)) {
            case APP -> Application.launch(CcaccApp.class, rest);
            case TUI -> UsageTrackerLauncher.main(Stream.concat(Stream.of("--cli"), Stream.of(rest)).toArray(String[]::new));
            case SERVE -> System.exit(new CommandLine(new CcLocalServerCli()).setCommandName("ccacc serve").execute(rest));
            case INSTALL -> System.exit(AppCommands.install(rest));
            case UNINSTALL -> System.exit(AppCommands.uninstall());
            case UPDATE -> System.exit(AppCommands.update());
            case CCU -> UsageTrackerLauncher.main(rest);
            case CCLS -> System.exit(new CommandLine(new CcLocalServerCli()).setCommandName("ccacc ccls").execute(rest));
            case MIGRATE -> System.exit(migrate(rest));
            case SWAP -> System.exit(swap(rest));
            case CONTROL -> System.exit(control(rest));
            case GATE -> System.exit(GateRoute.run(args));
            case FREE -> System.exit(FreeCli.run(rest));
            case CALLS -> System.exit(CallsCli.run(rest));
        }
    }

    /** A cswap command; the ones that change something go to the running app when there is one, so it stays the only writer. */
    static int swap(String[] rest) {
        var sandbox = java.util.Arrays.stream(rest).anyMatch(a -> a.startsWith("--store-dir") || a.startsWith("--claude-home") || a.startsWith("--credentials-xml"));
        if (Control.changesState(rest) && !sandbox) {
            var reply = ControlClient.send(Control.defaultFile(), java.util.List.of(rest));
            if (reply.isPresent()) return print(reply.get());
        }
        return new CommandLine(new ClaudeSwapCli()).setCommandName("ccacc").execute(rest);
    }

    /** mode, part and account: the running app does them; with none running the settings and the accounts file are changed on disk. */
    static int control(String[] rest) {
        var args = java.util.List.of(rest);
        var reply = ControlClient.send(Control.defaultFile(), args);
        if (reply.isPresent()) return print(reply.get());
        if (LaneCommands.handles(args)) return print(new LaneCommands(new CcaccSettingsStore(), null).run(args));
        try {
            var manager = new ccsuite.base.config.CredentialsManager();
            var accounts = new Accounts(manager, manager.credentialsFileExists() ? manager.load() : new ccsuite.base.model.CCAPIsCredentials(), () -> { });
            return print(new Commands(accounts, new CcaccSettingsStore(), null, null).run(args));
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }

    static int print(Control.Reply r) {
        if (r.ok()) { if (!r.message().isEmpty()) System.out.println(r.message()); return 0; }
        System.err.println("Error: " + r.message());
        return 1;
    }

    /** The old XML-based tools only ever ran on Windows; elsewhere there is nothing to retire. */
    static int migrate(String[] rest) {
        if (!ccsuite.usagetracker.ui.UsageWidget.WINDOWS) { System.out.println("Nothing to migrate: the old XML-based tools only existed on Windows."); return 0; }
        return ccsuite.ccacc.migrate.Migrate.run(rest);
    }

    static String overview() {
        try (var in = CcaccMain.class.getResourceAsStream("help.txt")) {
            return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            return "ccacc";
        }
    }

    private CcaccMain() {}
}
