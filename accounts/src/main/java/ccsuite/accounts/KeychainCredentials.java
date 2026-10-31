package ccsuite.accounts;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The login in the macOS Keychain, where Claude Code keeps it (generic password, service {@code Claude Code-credentials},
 * account = the user name), read and written with the system's {@code security} tool. The first access from another
 * program makes macOS ask once whether to allow it; "Always Allow" makes it stick.
 *
 * <p>When the Keychain has no such item but the credentials file exists (an older Claude Code, or a config directory set
 * by hand), the file is used instead, so nothing is lost either way.
 */
public final class KeychainCredentials implements CredentialsBackend {

    public static final String SERVICE = "Claude Code-credentials";
    /** {@code security} exits with this when the item does not exist. */
    static final int NOT_FOUND = 44;

    /** Runs a command: its exit code and standard output. */
    public interface Runner {
        Result run(List<String> command) throws IOException;
        record Result(int exit, String out, String err) {}
    }

    private final Runner runner;
    private final String service;
    private final String account;
    private final FileCredentials fallback;

    public KeychainCredentials(Runner runner, String service, String account, FileCredentials fallback) {
        this.runner = runner;
        this.service = service;
        this.account = account;
        this.fallback = fallback;
    }

    @Override public String read() throws IOException {
        var r = runner.run(List.of("security", "find-generic-password", "-s", service, "-a", account, "-w"));
        if (r.exit() == 0) {
            var text = r.out().strip();
            return text.isEmpty() ? null : text;
        }
        if (r.exit() == NOT_FOUND) return fallback.read();
        throw new IOException("the Keychain would not give the Claude Code login (security exit " + r.exit() + "): " + r.err().strip()
                + ". If macOS asked for permission, choose Always Allow.");
    }

    @Override public void write(String json) throws IOException {
        var where = runner.run(List.of("security", "find-generic-password", "-s", service, "-a", account));
        if (where.exit() == NOT_FOUND && fallback.exists()) { fallback.write(json); return; }
        // -U updates the item when it exists and creates it otherwise. The secret travels as an argument of this one
        // short-lived command, as with every command-line tool that writes the Keychain.
        var r = runner.run(List.of("security", "add-generic-password", "-U", "-s", service, "-a", account, "-w", json));
        if (r.exit() != 0)
            throw new IOException("could not save the Claude Code login in the Keychain (security exit " + r.exit() + "): " + r.err().strip());
    }

    @Override public String describe() { return "the macOS Keychain (" + service + ")"; }

    /** The real runner: starts the command and waits for it. */
    public static Runner system() {
        return command -> {
            try {
                var p = new ProcessBuilder(command).start();
                var out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                var err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
                return new Runner.Result(p.waitFor(), out, err);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            }
        };
    }
}
