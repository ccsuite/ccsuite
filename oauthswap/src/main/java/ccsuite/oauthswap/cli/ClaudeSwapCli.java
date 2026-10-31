package ccsuite.oauthswap.cli;

import com.fasterxml.jackson.databind.*;
import picocli.*;
import picocli.CommandLine.*;
import picocli.CommandLine.Model.CommandSpec;
import ccsuite.accounts.*;
import ccsuite.oauthswap.*;
import ccsuite.oauthclient.*;
import ccsuite.oauthswap.usage.*;
import ccsuite.base.config.CredentialsManager;
import ccsuite.base.model.CCAPIsCredentials;

import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * Root command. Global options exist mainly for safe testing: {@code --claude-home}
 * points the tool at a sandbox copy of the {@code .claude} files instead of the live
 * session, so a switch can be exercised without re-authing the running agent.
 */
@Command(name = "cswap",
         mixinStandardHelpOptions = true,
         version = "cswap 1.0",
         description = "Seamless multi-account switcher for Claude Code.",
         subcommands = {RegisterCmd.class, SwitchCmd.class, ListCmd.class, StatusCmd.class,
                        UsageCmd.class, WatchCmd.class})
public final class ClaudeSwapCli implements Callable<Integer> {

    @Option(names = "--store-dir", paramLabel = "DIR",
            description = "Override snapshot store root (default ~/xyz-jphil/ccapis).")
    Path storeDir;

    @Option(names = "--claude-home", paramLabel = "DIR",
            description = "Sandbox: use <DIR>/.claude and <DIR>/.claude.json instead of the live files.")
    Path claudeHome;

    @Option(names = "--credentials-xml", paramLabel = "FILE",
            description = "Override path to the credentials file (.json, or the older .xml).")
    Path credentialsXml;

    @Spec CommandSpec spec;

    SwapService service() {
        try {
            var mapper = new ObjectMapper()
                    .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            var paths = claudeHome != null ? ClaudeCodePaths.forHome(claudeHome) : ClaudeCodePaths.defaults();
            var live = new ClaudeLiveStore(paths, mapper);
            var root = storeDir != null ? storeDir : AccountStore.defaultStoreRoot();
            var store = new AccountStore(root, mapper);
            var mgr = credentialsXml != null ? new CredentialsManager(credentialsXml) : new CredentialsManager();
            CCAPIsCredentials creds = mgr.load();
            return new SwapService(live, store, creds, mgr.getCredentialsPath());
        } catch (Exception e) {
            throw new RuntimeException("Initialization failed: " + e.getMessage(), e);
        }
    }

    /** Live-quota client wired to the same live/store as {@code svc}, with the inference-probe
     *  fallback wired in too (see {@link UsageService#accountUsageWithFallback}). */
    UsageService usageService(SwapService svc) {
        var mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return new UsageService(new OAuthUsageClient(mapper), new InferenceProbeUsageClient(), svc.live(), svc.store());
    }

    @Override public Integer call() { spec.commandLine().usage(System.out); return 0; }

    public static void main(String[] args) {
        System.exit(new CommandLine(new ClaudeSwapCli()).execute(args));
    }
}
