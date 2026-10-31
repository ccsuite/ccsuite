package ccsuite.accounts;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * A stored snapshot of one Claude Code login, keyed by its CCAPICredential {@code id}.
 * Holds the raw {@code .credentials.json} payload (the OAuth token set) and the
 * {@code oauthAccount} identity block verbatim, so a switch restores them exactly.
 * Persisted as {@code <store>/claudecode-accounts/<id>.json}.
 */
@Data
@Accessors(fluent = true, chain = true)
@JsonAutoDetect(fieldVisibility = Visibility.ANY, getterVisibility = Visibility.NONE,
                isGetterVisibility = Visibility.NONE, setterVisibility = Visibility.NONE)
public class ClaudeCodeAccount {
    String id;
    String email;
    String organizationUuid;
    String accountUuid;
    String registeredAt;
    JsonNode credentials;
    JsonNode oauthAccount;
}
