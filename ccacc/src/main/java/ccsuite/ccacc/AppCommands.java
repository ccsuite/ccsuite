package ccsuite.ccacc;

import jarrunner.jr.apputils.Install;
import jarrunner.jr.apputils.Updater;

import java.util.List;

/** The exe's own life as a jr app: put it in the user's standard place, take it out again, bring it to the newest release. No administrator rights. */
final class AppCommands {

    static final String NAME = "ccacc";

    /** Copies the running exe to the user's programs folder, onto the PATH, with a Start Menu entry; {@code --start-at-login} also starts the app when the user signs in. */
    static int install(String[] args) {
        try {
            var install = Install.app(NAME).displayName(NAME).description("Claude Code accounts: usage, switching, local proxy")
                    .menuShortcut(true).desktopShortcut(false).onPath(true).log(System.out::println);
            if (List.of(args).contains("--start-at-login")) install.startAtLogin("app");
            return install.run().ok() ? 0 : 1;
        } catch (RuntimeException e) {
            System.err.println("install failed: " + e);
            return 1;
        }
    }

    static int uninstall() {
        try {
            return Install.uninstall(NAME, System.out::println).ok() ? 0 : 1;
        } catch (RuntimeException e) {
            System.err.println("uninstall failed: " + e);
            return 1;
        }
    }

    /** Asks jr for the newest release of this exe and applies it; it runs from the next start. */
    static int update() {
        try {
            var r = Updater.update();
            var note = Updater.notice(r, "ccacc update");
            if (note != null) System.out.println(note);
            return r.ok() ? 0 : 1;
        } catch (RuntimeException e) {
            System.err.println("update failed: " + e);
            return 1;
        }
    }

    private AppCommands() {}
}
