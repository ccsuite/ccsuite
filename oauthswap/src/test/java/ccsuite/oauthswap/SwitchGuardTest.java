package ccsuite.oauthswap;

import ccsuite.accounts.*;
import ccsuite.base.model.*;
import ccsuite.oauthclient.OAuthUsageClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The 2026-10-31 failure: the saved login of BOB held ANN's tokens under BOB's name, so every "switch to BOB" logged
 * Claude Code in as ANN and the tool reported an automatic switch back. Ownership is whatever Anthropic says for the
 * access token; the labels written next to the tokens prove nothing.
 */
class SwitchGuardTest {

    static final ObjectMapper M = new ObjectMapper();
    static final long FAR = System.currentTimeMillis() + 6 * 3600_000L;

    @TempDir Path home;
    ClaudeLiveStore live;
    AccountStore store;
    CCAPIsCredentials creds;
    /** access token to the real owner, as Anthropic would answer. */
    final Map<String, String> owners = new java.util.HashMap<>();

    @BeforeEach void setUp() throws Exception {
        var paths = ClaudeCodePaths.forHome(home);
        live = new ClaudeLiveStore(paths, M);
        store = new AccountStore(home.resolve("accounts"), M);
        creds = new CCAPIsCredentials();
        for (var id : new String[]{"ann", "bob", "cat"})
            creds.credentials().add(new CCAPICredential().id(id).email(id + "@example.org").trackUsage(true).tier(1).active(true));
    }

    SwapService service() {
        return new SwapService(live, store, creds, null, new OAuthUsageClient(M), owners::get);
    }

    static JsonNode tokens(String access, String refresh) throws Exception {
        return M.readTree("{\"claudeAiOauth\":{\"accessToken\":\"" + access + "\",\"refreshToken\":\"" + refresh
                + "\",\"expiresAt\":" + FAR + "}}");
    }

    static JsonNode label(String email) throws Exception {
        return M.readTree("{\"emailAddress\":\"" + email + "\",\"organizationUuid\":\"org\"}");
    }

    void snapshot(String id, String email, JsonNode tokens) throws Exception {
        store.save(new ClaudeCodeAccount().id(id).email(email).credentials(tokens).oauthAccount(label(email)));
    }

    void loginAs(String labelEmail, JsonNode tokens) throws Exception {
        live.writeCredentials(tokens);
        live.writeOauthAccount(label(labelEmail));
    }

    @Test void aSnapshotHoldingAnotherAccountsTokensIsRefusedAndTheLiveLoginIsUntouched() throws Exception {
        owners.put("ann-access", "ann@example.org");
        snapshot("ann", "ann@example.org", tokens("ann-access", "ann-refresh"));
        snapshot("bob", "bob@example.org", tokens("ann-access", "ann-refresh"));   // poisoned: ANN's tokens under BOB's name
        loginAs("ann@example.org", tokens("ann-access", "ann-refresh"));

        var e = assertThrows(IllegalStateException.class, () -> service().switchTo("bob"));
        assertTrue(e.getMessage().contains("same tokens as ann"), e.getMessage());
        assertEquals("ann", service().detectActiveId());
        assertEquals("ann-access", live.readCredentials().path("claudeAiOauth").path("accessToken").asText());
    }

    @Test void aSnapshotWhoseFreshTokenBelongsToSomeoneElseIsRefusedEvenWhenNoSnapshotRepeatsIt() throws Exception {
        owners.put("ann-access", "ann@example.org");
        owners.put("old-ann-access", "ann@example.org");
        snapshot("ann", "ann@example.org", tokens("ann-access", "ann-refresh"));
        snapshot("bob", "bob@example.org", tokens("old-ann-access", "older-ann-refresh"));   // ANN's rotated tokens
        loginAs("ann@example.org", tokens("ann-access", "ann-refresh"));

        var e = assertThrows(IllegalStateException.class, () -> service().switchTo("bob"));
        assertTrue(e.getMessage().contains("holds the tokens of ann@example.org"), e.getMessage());
        assertEquals("ann-access", live.readCredentials().path("claudeAiOauth").path("accessToken").asText());
    }

    @Test void aHonestSnapshotSwitchesAndTheOutgoingAccountKeepsItsRefreshedTokens() throws Exception {
        owners.put("ann-access", "ann@example.org");
        owners.put("ann-access-2", "ann@example.org");
        owners.put("bob-access", "bob@example.org");
        snapshot("ann", "ann@example.org", tokens("ann-access", "ann-refresh"));
        snapshot("bob", "bob@example.org", tokens("bob-access", "bob-refresh"));
        loginAs("ann@example.org", tokens("ann-access-2", "ann-refresh-2"));            // Claude Code refreshed since the snapshot

        var r = service().switchTo("bob");
        assertEquals("ann", r.fromId());
        assertEquals("bob-access", live.readCredentials().path("claudeAiOauth").path("accessToken").asText());
        assertEquals("ann-refresh-2", store.load("ann").credentials().path("claudeAiOauth").path("refreshToken").asText());
    }

    @Test void theOutgoingSnapshotIsNotOverwrittenWithTokensThatAreNotItsOwn() throws Exception {
        // the identity label says CAT, but the tokens in the live file are BOB's (a session saved its own login back)
        owners.put("bob-access", "bob@example.org");
        owners.put("ann-access", "ann@example.org");
        snapshot("cat", "cat@example.org", tokens("cat-access", "cat-refresh"));
        snapshot("ann", "ann@example.org", tokens("ann-access", "ann-refresh"));
        loginAs("cat@example.org", tokens("bob-access", "bob-refresh"));

        assertEquals("bob", service().detectActiveId(), "the tokens decide, not the label");
        service().switchTo("ann");
        assertEquals("cat-refresh", store.load("cat").credentials().path("claudeAiOauth").path("refreshToken").asText());
    }

    @Test void theLiveLoginIsTakenFromTheTokensNotTheLabel() throws Exception {
        owners.put("bob-access", "bob@example.org");
        loginAs("ann@example.org", tokens("bob-access", "bob-refresh"));
        assertEquals("bob", service().detectActiveId());
    }

    @Test void whenAnthropicCannotBeAskedTheLabelIsUsedAsBefore() throws Exception {
        loginAs("cat@example.org", tokens("cat-access", "cat-refresh"));
        assertEquals("cat", service().detectActiveId());
    }

    @Test void registeringALoginWhoseTokensAreNotTheLabelledAccountsIsRefused() throws Exception {
        owners.put("ann-access", "ann@example.org");
        loginAs("bob@example.org", tokens("ann-access", "ann-refresh"));
        var e = assertThrows(IllegalStateException.class, () -> service().register(Optional.of("bob")));
        assertTrue(e.getMessage().contains("belong to ann@example.org"), e.getMessage());
        assertFalse(store.isRegistered("bob"));
    }

    @Test void registeringAnHonestLoginWorks() throws Exception {
        owners.put("bob-access", "bob@example.org");
        loginAs("bob@example.org", tokens("bob-access", "bob-refresh"));
        assertEquals("bob", service().register(Optional.empty()).id());
        assertTrue(store.isRegistered("bob"));
    }
}
