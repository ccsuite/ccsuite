package ccsuite.oauthswap;

import ccsuite.accounts.*;

import com.fasterxml.jackson.databind.*;
import org.slf4j.*;
import ccsuite.oauthclient.*;
import ccsuite.base.config.CredentialsActiveFlagWriter;
import ccsuite.base.model.*;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

/**
 * Orchestrates register / switch / list / status. The currently-active account is
 * always derived <em>live</em> from {@code oauthAccount.emailAddress} matched against
 * the eligible set, so it never drifts from a stale state file.
 */
public final class SwapService {

    private static final Logger log = LoggerFactory.getLogger(SwapService.class);

    private final ClaudeLiveStore live;
    private final AccountStore store;
    private final CCAPIsCredentials creds;
    private final Path credentialsXml;
    private final OAuthUsageClient oauthClient;
    private final LoginOwner owner;

    public SwapService(ClaudeLiveStore live, AccountStore store, CCAPIsCredentials creds, Path credentialsXml) {
        this(live, store, creds, credentialsXml, new OAuthUsageClient(new ObjectMapper()));
    }

    public SwapService(ClaudeLiveStore live, AccountStore store, CCAPIsCredentials creds, Path credentialsXml,
                        OAuthUsageClient oauthClient) {
        this(live, store, creds, credentialsXml, oauthClient, oauthClient::fetchProfileEmail);
    }

    /** {@code profileEmail}: access token to the e-mail Anthropic gives for it, {@code null} when unknown. */
    SwapService(ClaudeLiveStore live, AccountStore store, CCAPIsCredentials creds, Path credentialsXml,
                OAuthUsageClient oauthClient, java.util.function.Function<String, String> profileEmail) {
        this.live = live;
        this.store = store;
        this.creds = creds;
        this.credentialsXml = credentialsXml;
        this.oauthClient = oauthClient;
        this.owner = new LoginOwner(profileEmail);
    }

    public List<CCAPICredential> eligible() { return EligibleAccounts.eligible(creds); }

    /** The accounts Claude Code may be switched to and the local proxy may serve from: eligible and not monitor-only. */
    public List<CCAPICredential> servable() { return EligibleAccounts.servable(creds); }

    public boolean isServable(String id) { return servable().stream().anyMatch(c -> c.id().equals(id)); }

    public ClaudeLiveStore live() { return live; }
    public AccountStore store() { return store; }

    /** id of the account Claude Code is currently logged into, or {@code null} if unmanaged. */
    public String detectActiveId() throws IOException {
        var oauth = live.readOauthAccount();
        if (oauth == null) return null;
        // The label in Claude Code's global file can disagree with the tokens next to it. What the tokens belong to wins.
        var email = Optional.ofNullable(owner.ownerOf(live.readCredentials())).orElse(text(oauth, "emailAddress"));
        var orgUuid = text(oauth, "organizationUuid");
        var matches = EligibleAccounts.matchByEmail(eligible(), email);
        if (matches.isEmpty()) return null;
        if (matches.size() == 1) return matches.get(0).id();
        return matches.stream().filter(c -> Objects.equals(orgUuid, c.orgId()))
                .findFirst().orElse(matches.get(0)).id();
    }

    /** Snapshot the current login, tying it to credential {@code idOpt} (auto-matched if absent). */
    public CCAPICredential register(Optional<String> idOpt) throws IOException {
        if (!live.hasLogin())
            throw new IllegalStateException("No Claude Code login found. Log into Claude Code first, then re-run.");
        var credsNode = live.readCredentials();
        var oauth = live.readOauthAccount();
        var email = text(oauth, "emailAddress");
        var tokensOwner = owner.ownerOf(credsNode);
        if (tokensOwner != null && !tokensOwner.equalsIgnoreCase(email))
            throw new IllegalStateException("The tokens in the live login belong to " + tokensOwner + ", but Claude Code's "
                    + "identity block says " + email + ". Not registering a mixed-up login: log in again in Claude Code "
                    + "as the account you mean, then re-run.");

        var target = resolveTarget(idOpt, email);
        if (tokensOwner != null && !tokensOwner.equalsIgnoreCase(target.email()))
            throw new IllegalStateException("The live login is " + tokensOwner + ", not " + target.id() + " ("
                    + target.email() + "). Log in as " + target.id() + " in Claude Code first, then register it.");
        var account = new ClaudeCodeAccount()
                .id(target.id())
                .email(email != null ? email : target.email())
                .organizationUuid(text(oauth, "organizationUuid"))
                .accountUuid(text(oauth, "accountUuid"))
                .registeredAt(Instant.now().toString())
                .credentials(credsNode)
                .oauthAccount(oauth);
        store.save(account);
        log.info("Registered Claude Code account {} ({})", target.id(), account.email());
        return target;
    }

    private CCAPICredential resolveTarget(Optional<String> idOpt, String email) {
        if (idOpt.isPresent()) {
            var id = idOpt.get();
            var c = EligibleAccounts.byId(creds, id).orElseThrow(() ->
                    new IllegalArgumentException("Unknown credential id '" + id + "' in the credentials file"));
            if (!(c.active() && c.trackUsage() && c.tier() >= 1))
                log.warn("Account {} is not eligible (active+trackUsage+tier>=1); registering anyway", id);
            return c;
        }
        if (email == null || email.isBlank())
            throw new IllegalStateException("Could not read the current account email; pass --id <ID>");
        var matches = EligibleAccounts.matchByEmail(eligible(), email);
        if (matches.size() == 1) return matches.get(0);
        if (matches.isEmpty())
            throw new IllegalStateException("Logged-in email " + email
                    + " matches no eligible account. Pass --id <ID> to register it explicitly.");
        throw new IllegalStateException("Email " + email + " matches multiple accounts ("
                + ids(matches) + "); disambiguate with --id <ID>");
    }

    public SwitchResult switchTo(String id) throws IOException {
        if (EligibleAccounts.byId(creds, id).map(CCAPICredential::watchOnly).orElse(false))
            throw new IllegalArgumentException("Account '" + id + "' is monitor-only: it is watched but never switched to. Turn monitor-only off for it first.");
        if (!store.isRegistered(id))
            throw new IllegalArgumentException("Account '" + id + "' is not registered. "
                    + "Log into it in Claude Code, then run: cswap register --id " + id);
        var target = store.load(id);
        var fromId = detectActiveId();
        // Check the target before touching anything: a snapshot holding another account's tokens would install that
        // account's login under this account's name, and the account being left would come straight back.
        requireDistinctTokens(target);
        var incoming = freshenIfStale(target);
        requireOwnerMatches(target, incoming);
        if (fromId != null && !fromId.equals(id) && store.isRegistered(fromId))
            resnapshot(fromId);
        live.writeCredentials(incoming);
        if (target.oauthAccount() != null) live.writeOauthAccount(target.oauthAccount());
        log.info("Switched Claude Code from {} to {} ({})", fromId, id, target.email());
        markActiveInCredentials(id);
        return new SwitchResult(fromId, id, target.email());
    }

    /** Never promote a stale access token to the live login — refresh (and re-persist) it first,
     *  so whatever's using Claude Code right after a switch doesn't inherit an already-dead token
     *  (a snapshot can go stale simply from sitting unused while other accounts were active). */
    private JsonNode freshenIfStale(ClaudeCodeAccount target) {
        var creds = target.credentials();
        if (!OAuthCredentials.expired(creds)) return creds;
        var refreshed = oauthClient.refresh(creds);
        if (refreshed == null) return creds;
        try {
            store.save(target.credentials(refreshed));
            log.info("Refreshed {}'s token before promoting it to the live login", target.id());
        } catch (IOException e) {
            log.warn("Refreshed {}'s token but failed to persist: {}", target.id(), e.toString());
        }
        return refreshed;
    }

    /** Mirror the just-activated account into CCAPIsCredentials.xml so the usage tracker
     *  lists it first. Best-effort: ordering is cosmetic and must never fail a switch. */
    private void markActiveInCredentials(String id) {
        if (credentialsXml == null) return;
        try {
            if (CredentialsActiveFlagWriter.setActive(credentialsXml, id))
                log.info("Marked {} as claudecodeactive in {}", id, credentialsXml);
        } catch (Exception e) {
            log.warn("Could not update claudecodeactive in {}: {}", credentialsXml, e.toString());
        }
    }

    public SwitchResult switchNext() throws IOException {
        var order = servable().stream().filter(c -> store.isRegistered(c.id())).toList();
        if (order.isEmpty())
            throw new IllegalStateException("No registered accounts to rotate. Register at least one with: cswap register");
        var fromId = detectActiveId();
        var idx = -1;
        for (var i = 0; i < order.size(); i++) if (order.get(i).id().equals(fromId)) idx = i;
        var next = order.get((idx + 1) % order.size());
        return switchTo(next.id());
    }

    /** Offline, and before any refresh: refreshing tokens that are another account's would invalidate that account's own copy. */
    private void requireDistinctTokens(ClaudeCodeAccount target) throws IOException {
        var clash = otherAccountWithSameTokens(target.id(), target.credentials());
        if (clash != null)
            throw new IllegalStateException("The saved login of " + target.id() + " has the same tokens as " + clash
                    + ", so one of them is not its own. Log in as " + target.id() + " in Claude Code, then run: ccacc register --id "
                    + target.id() + " (and check " + clash + ").");
    }

    /** Refuse to install a snapshot whose tokens, once fresh, Anthropic says belong to someone else. */
    private void requireOwnerMatches(ClaudeCodeAccount target, JsonNode tokens) {
        var verdict = owner.verdict(tokens, target.email());
        if (verdict == LoginOwner.Verdict.MISMATCH)
            throw new IllegalStateException("The saved login of " + target.id() + " (" + target.email() + ") holds the tokens of "
                    + owner.ownerOf(tokens) + ", so switching to it would log Claude Code in as that account. "
                    + "Log in as " + target.id() + " in Claude Code, then run: ccacc register --id " + target.id());
    }

    /** The id of another registered account whose saved tokens are these very tokens, or {@code null}. */
    private String otherAccountWithSameTokens(String id, JsonNode tokens) throws IOException {
        var refresh = LoginOwner.refreshToken(tokens);
        if (refresh == null) return null;
        for (var otherId : store.registeredIds()) {
            if (otherId.equals(id)) continue;
            if (refresh.equals(LoginOwner.refreshToken(store.load(otherId).credentials()))) return otherId;
        }
        return null;
    }

    /** Re-capture the outgoing account's current (possibly refreshed) tokens. Best-effort, and only when they are its own. */
    private void resnapshot(String id) {
        try {
            var existing = store.load(id);
            var liveTokens = live.readCredentials();
            var verdict = owner.verdict(liveTokens, existing.email());
            if (verdict == LoginOwner.Verdict.MISMATCH
                    || (verdict == LoginOwner.Verdict.UNKNOWN && otherAccountWithSameTokens(id, liveTokens) != null)) {
                log.warn("Not re-snapshotting {}: the live tokens are not its own (they belong to {})", id, owner.ownerOf(liveTokens));
                return;
            }
            existing.credentials(liveTokens).oauthAccount(live.readOauthAccount());
            store.save(existing);
        } catch (Exception e) {
            log.warn("Could not re-snapshot outgoing account {}: {}", id, e.toString());
        }
    }

    public List<AccountView> overview() throws IOException {
        var activeId = detectActiveId();
        var views = new ArrayList<AccountView>();
        for (var c : eligible())
            views.add(new AccountView(c.id(), c.email(), c.name(), c.orgId(),
                    c.claudecodelimit(), store.isRegistered(c.id()), c.id().equals(activeId)));
        return views;
    }

    private static String text(JsonNode node, String field) {
        if (node == null) return null;
        var v = node.get(field);
        return (v == null || v.isNull()) ? null : v.asText();
    }

    private static String ids(List<CCAPICredential> list) {
        return String.join(", ", list.stream().map(CCAPICredential::id).toList());
    }
}
