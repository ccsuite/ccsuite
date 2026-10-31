package ccsuite.accounts;

import java.nio.file.*;

/**
 * Resolves the Claude Code files this tool reads/writes, mirroring Claude Code's
 * own resolution so we touch exactly the files it does:
 * <ul>
 *   <li>config dir: {@code CLAUDE_CONFIG_DIR} if set, else {@code <home>/.claude}</li>
 *   <li>credentials: {@code <configDir>/.credentials.json} (the OAuth token store)</li>
 *   <li>global config: legacy {@code <configDir>/.config.json} if present, else
 *       {@code <home>/.claude.json} (carries {@code oauthAccount} identity)</li>
 * </ul>
 * A sandbox instance ({@link #forHome}) ignores the env var so tests/dry-runs are
 * fully isolated from the live session.
 */
public final class ClaudeCodePaths {

    final Path configDir;
    final Path credentialsFile;
    final Path globalConfigFile;
    final CredentialsBackend backend;

    private ClaudeCodePaths(Path configDir, Path credentialsFile, Path globalConfigFile, CredentialsBackend backend) {
        this.configDir = configDir;
        this.credentialsFile = credentialsFile;
        this.globalConfigFile = globalConfigFile;
        this.backend = backend;
    }

    /** macOS keeps Claude Code's login in the Keychain, unless the config directory was set by hand (then the file in it is used). */
    static boolean keychainHere(boolean configDirSet) {
        return !configDirSet && System.getProperty("os.name", "").toLowerCase().contains("mac");
    }

    /** Live paths for the current user, honoring {@code CLAUDE_CONFIG_DIR}. */
    public static ClaudeCodePaths defaults() {
        var home = Paths.get(System.getProperty("user.home"));
        var env = System.getenv("CLAUDE_CONFIG_DIR");
        var cfgDir = (env != null && !env.isBlank()) ? Paths.get(env) : home.resolve(".claude");
        var legacy = cfgDir.resolve(".config.json");
        var global = Files.exists(legacy) ? legacy : home.resolve(".claude.json");
        var file = new FileCredentials(cfgDir.resolve(".credentials.json"));
        CredentialsBackend backend = keychainHere(env != null && !env.isBlank())
                ? new KeychainCredentials(KeychainCredentials.system(), KeychainCredentials.SERVICE, System.getProperty("user.name"), file) : file;
        return new ClaudeCodePaths(cfgDir, cfgDir.resolve(".credentials.json"), global, backend);
    }

    /** Sandbox paths rooted at {@code home} ({@code home/.claude/...}, {@code home/.claude.json}). */
    public static ClaudeCodePaths forHome(Path home) {
        var cfgDir = home.resolve(".claude");
        var legacy = cfgDir.resolve(".config.json");
        var global = Files.exists(legacy) ? legacy : home.resolve(".claude.json");
        return new ClaudeCodePaths(cfgDir, cfgDir.resolve(".credentials.json"), global, new FileCredentials(cfgDir.resolve(".credentials.json")));
    }

    /** Where the login really is: the file, or the macOS Keychain. */
    public CredentialsBackend backend() { return backend; }

    public Path credentialsFile()  { return credentialsFile; }
    public Path globalConfigFile() { return globalConfigFile; }
    public Path configDir()        { return configDir; }
}
