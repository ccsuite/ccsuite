package ccsuite.base.model;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.experimental.Accessors;
import jakarta.xml.bind.annotation.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Root wrapper for shared credentials file.
 * Maps to CCAPIsCredentials.xml in <userhome>/xyz-jphil/ccapis/
 *
 * Supports both regular session-based credentials (CCAPICredential)
 * and OAuth credentials (CCAPICredentialOauth).
 */
@Data
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY, getterVisibility = JsonAutoDetect.Visibility.NONE, isGetterVisibility = JsonAutoDetect.Visibility.NONE, setterVisibility = JsonAutoDetect.Visibility.NONE)
@JsonInclude(JsonInclude.Include.NON_NULL)
@Accessors(fluent = true, chain = true)
@XmlRootElement(name = "CCAPIsCredentials")
@XmlAccessorType(XmlAccessType.FIELD)
public class CCAPIsCredentials {

    @XmlElement(name = "CircuitBreakerConfig")
    private CircuitBreakerConfig circuitBreakerConfig;

    @XmlElement(name = "CCAPICredential")
    private List<CCAPICredential> credentials = new ArrayList<>();

    @XmlElement(name = "CCAPICredentialOauth")
    private List<CCAPICredentialOauth> oauthCredentials = new ArrayList<>();

    /**
     * Get all active credentials
     */
    public List<CCAPICredential> getActiveCredentials() {
        return credentials.stream()
                .filter(CCAPICredential::active)
                .toList();
    }

    /**
     * Get all credentials with usage tracking enabled
     */
    public List<CCAPICredential> getTrackUsageCredentials() {
        return credentials.stream()
                .filter(CCAPICredential::trackUsage)
                .toList();
    }

    /**
     * Get all credentials with usage tracking enabled, ordered so the account(s)
     * Claude Code is currently active on (claudecodeactive=true) come first.
     * Stable: original file order is preserved within each group.
     */
    public List<CCAPICredential> getTrackUsageCredentialsOrdered() {
        return credentials.stream()
                .filter(CCAPICredential::trackUsage)
                .sorted(Comparator.comparing((CCAPICredential c) -> !c.claudecodeactive()))
                .toList();
    }

    /**
     * Get all credentials with chat tracking enabled
     */
    public List<CCAPICredential> getTrackChatsCredentials() {
        return credentials.stream()
                .filter(CCAPICredential::trackChats)
                .toList();
    }

    /**
     * Get all credentials with ping enabled
     */
    public List<CCAPICredential> getPingEnabledCredentials() {
        return credentials.stream()
                .filter(CCAPICredential::ping)
                .toList();
    }

    /**
     * Get credential by ID
     */
    public CCAPICredential getById(String id) {
        return credentials.stream()
                .filter(c -> id.equals(c.id()))
                .findFirst()
                .orElse(null);
    }

    /**
     * Get all active OAuth credentials
     */
    public List<CCAPICredentialOauth> getActiveOauthCredentials() {
        return oauthCredentials.stream()
                .filter(CCAPICredentialOauth::active)
                .toList();
    }

    /**
     * Get all OAuth credentials with usage tracking enabled
     */
    public List<CCAPICredentialOauth> getTrackUsageOauthCredentials() {
        return oauthCredentials.stream()
                .filter(CCAPICredentialOauth::trackUsage)
                .toList();
    }

    /**
     * Get all OAuth credentials with ping enabled
     */
    public List<CCAPICredentialOauth> getPingEnabledOauthCredentials() {
        return oauthCredentials.stream()
                .filter(CCAPICredentialOauth::ping)
                .toList();
    }

    /**
     * Get OAuth credential by ID
     */
    public CCAPICredentialOauth getOauthById(String id) {
        return oauthCredentials.stream()
                .filter(c -> id.equals(c.id()))
                .findFirst()
                .orElse(null);
    }

    /**
     * Get circuit breaker configuration with fallback to defaults
     */
    public CircuitBreakerConfig getCircuitBreakerConfig() {
        return circuitBreakerConfig != null ? circuitBreakerConfig : new CircuitBreakerConfig();
    }
}
