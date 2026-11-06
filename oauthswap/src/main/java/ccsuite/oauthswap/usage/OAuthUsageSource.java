package ccsuite.oauthswap.usage;

import ccsuite.accounts.AccountStore;
import ccsuite.accounts.ClaudeCodePaths;
import ccsuite.accounts.ClaudeLiveStore;
import ccsuite.base.config.CredentialsManager;
import ccsuite.base.model.Usage;
import ccsuite.base.model.UsageData;
import ccsuite.base.usage.UsageSource;
import ccsuite.oauthclient.AccountUsage;
import ccsuite.oauthclient.InferenceProbeUsageClient;
import ccsuite.oauthclient.OAuthUsageClient;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.OffsetDateTime;

/**
 * Usage from Claude Code's own side: the account's OAuth token (live or saved) read through
 * {@link UsageService}, answered in the {@link UsageData} shape the cookie line
 * also speaks. Each call may cost a real, tiny completion, so every fetch is gated by the
 * per-account cooldown {@link UsageService} keeps; a fetch inside the cooldown returns nothing
 * rather than reaching the network.
 */
public final class OAuthUsageSource implements UsageSource {

    private final UsageService service;
    private final CredentialsManager credentials;

    public OAuthUsageSource(UsageService service, CredentialsManager credentials) {
        this.service = service;
        this.credentials = credentials;
    }

    /** The usual wiring: this machine's Claude Code login, the saved accounts, the shared credentials file. */
    public static OAuthUsageSource standard() {
        var mapper = new ObjectMapper();
        var live = new ClaudeLiveStore(ClaudeCodePaths.defaults(), mapper);
        var store = new AccountStore(AccountStore.defaultStoreRoot(), mapper);
        var service = new UsageService(new OAuthUsageClient(mapper), new InferenceProbeUsageClient(), live, store);
        return new OAuthUsageSource(service, new CredentialsManager());
    }

    /** One attempt, gated by the automatic cooldown for this account; throws when gated or when the probe fails. */
    @Override
    public UsageData fetchUsage(String accountId) throws IOException {
        var active = isActive(accountId);
        var cooldownMs = active ? UsageService.AUTO_ACTIVE_FALLBACK_COOLDOWN_MS : UsageService.AUTO_OTHER_FALLBACK_COOLDOWN_MS;
        var usage = fetchIfDue(accountId, cooldownMs);
        if (usage == null) throw new IOException("no usage from the Claude Code side for " + accountId + " (cooldown or no login)");
        return usage;
    }

    /** The account's usage, or {@code null} when it was already fetched within {@code cooldownMs} or no token is usable. */
    public UsageData fetchIfDue(String accountId, long cooldownMs) throws IOException {
        try {
            var probed = service.probeFallbackIfDue(accountId, isActive(accountId), cooldownMs);
            return probed == null ? null : toUsageData(probed);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while fetching usage for " + accountId, e);
        }
    }

    /** Like {@link #fetchIfDue}, but inside the cooldown it answers with the latest reading anyone (the watcher too) got, instead of nothing. */
    public UsageData fetchOrLastKnown(String accountId, long cooldownMs) throws IOException {
        var fresh = fetchIfDue(accountId, cooldownMs);
        if (fresh != null) return fresh;
        var last = service.lastKnown(accountId);
        return last == null ? null : toUsageData(last);
    }

    @Override
    public void close() {
    }

    /** Whether Claude Code is currently logged into this account, as recorded in the shared credentials file. */
    private boolean isActive(String accountId) {
        try {
            var all = credentials.load();
            var credential = all == null ? null : all.getById(accountId);
            return credential != null && credential.claudecodeactive();
        } catch (Exception e) {
            return false;
        }
    }

    static UsageData toUsageData(AccountUsage probeUsage) {
        var data = new UsageData();
        if (probeUsage.fiveHourPct() != null) {
            data.fiveHour(new Usage().utilization(probeUsage.fiveHourPct())
                    .resetsAt(parseIsoOrNull(probeUsage.fiveHourResetsAt())));
        }
        if (probeUsage.sevenDayPct() != null) {
            data.sevenDay(new Usage().utilization(probeUsage.sevenDayPct())
                    .resetsAt(parseIsoOrNull(probeUsage.sevenDayResetsAt())));
        }
        return data;
    }

    private static OffsetDateTime parseIsoOrNull(String iso) {
        if (iso == null) return null;
        try {
            return OffsetDateTime.parse(iso);
        } catch (Exception e) {
            return null;
        }
    }
}
