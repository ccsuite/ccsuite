package ccsuite.oauthswap;

import ccsuite.oauthclient.OAuthCredentials;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Whose login a set of tokens really is. The e-mail written next to the tokens ({@code oauthAccount} in Claude
 * Code's global file, or in a saved snapshot) is only a label: a running Claude Code session can save its own tokens
 * over the live file while the label stays, and a snapshot taken then holds one account's tokens under another
 * account's name. Switching to such a snapshot installs the wrong login (the account the user left comes straight
 * back). The only authority is the answer Anthropic gives for the access token itself.
 *
 * <p>The answers are cached by access token (a token belongs to one account for its whole life), so the live file
 * can be asked about on every poll for the price of one request per new token. A failed lookup is remembered for a
 * minute so being offline costs one attempt a minute, not one per poll.
 */
final class LoginOwner {

    enum Verdict { MATCH, MISMATCH, UNKNOWN }

    private static final long RETRY_UNKNOWN_MS = 60_000;
    private static final int KEEP = 16;

    private final Function<String, String> profileEmail;
    private final Map<String, String> known = new HashMap<>();
    private final Map<String, Long> failedAt = new HashMap<>();

    LoginOwner(Function<String, String> profileEmail) { this.profileEmail = profileEmail; }

    /** The e-mail Anthropic says these credentials belong to, or {@code null} when that cannot be told now. */
    synchronized String ownerOf(JsonNode credentials) {
        var token = OAuthCredentials.accessToken(credentials);
        if (token == null || OAuthCredentials.expired(credentials)) return null;
        var cached = known.get(token);
        if (cached != null) return cached;
        var failed = failedAt.get(token);
        if (failed != null && System.currentTimeMillis() - failed < RETRY_UNKNOWN_MS) return null;
        var email = profileEmail.apply(token);
        if (email == null) {
            failedAt.put(token, System.currentTimeMillis());
            if (failedAt.size() > KEEP) failedAt.clear();
            return null;
        }
        if (known.size() > KEEP) known.clear();
        known.put(token, email.toLowerCase());
        failedAt.remove(token);
        return email.toLowerCase();
    }

    Verdict verdict(JsonNode credentials, String expectedEmail) {
        var owner = ownerOf(credentials);
        if (owner == null || expectedEmail == null || expectedEmail.isBlank()) return Verdict.UNKNOWN;
        return owner.equalsIgnoreCase(expectedEmail) ? Verdict.MATCH : Verdict.MISMATCH;
    }

    /** The refresh token, which is unique to one login; {@code null} when there is none. */
    static String refreshToken(JsonNode credentials) {
        var oauth = credentials == null ? null : credentials.get("claudeAiOauth");
        var t = oauth == null ? null : oauth.get("refreshToken");
        return (t == null || t.isNull() || t.asText().isBlank()) ? null : t.asText();
    }
}
