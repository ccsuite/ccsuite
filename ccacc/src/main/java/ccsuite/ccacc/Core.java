package ccsuite.ccacc;

import ccsuite.accounts.AccountStore;
import ccsuite.accounts.ClaudeCodePaths;
import ccsuite.accounts.ClaudeLiveStore;
import ccsuite.base.config.CredentialsManager;
import ccsuite.base.model.CCAPIsCredentials;
import ccsuite.oauthclient.InferenceProbeUsageClient;
import ccsuite.oauthclient.OAuthUsageClient;
import ccsuite.oauthswap.SwapService;
import ccsuite.oauthswap.usage.OAuthUsageSource;
import ccsuite.oauthswap.usage.UsageService;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * What every part shares: the login files, the saved accounts and ONE usage service, so the watcher,
 * the widget and the proxy keep one per-account probe cooldown between them instead of probing three times.
 */
final class Core {

    final CredentialsManager credentials = new CredentialsManager();
    /** The accounts as loaded, one object shared by the switcher and the account manager, so an edit is seen by both at once. */
    final CCAPIsCredentials creds;
    final ClaudeLiveStore live;
    final AccountStore store;
    final SwapService swap;
    final OAuthUsageClient oauth;
    final UsageService usage;
    final OAuthUsageSource usageSource;

    Core() throws Exception {
        var mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        live = new ClaudeLiveStore(ClaudeCodePaths.defaults(), mapper);
        store = new AccountStore(AccountStore.defaultStoreRoot(), mapper);
        creds = credentials.load();
        creds.credentials(new CopyOnWriteArrayList<>(creds.credentials()));
        swap = new SwapService(live, store, creds, credentials.getCredentialsPath());
        oauth = new OAuthUsageClient(mapper);
        usage = new UsageService(oauth, new InferenceProbeUsageClient(), live, store);
        usageSource = new OAuthUsageSource(usage, credentials);
    }
}
