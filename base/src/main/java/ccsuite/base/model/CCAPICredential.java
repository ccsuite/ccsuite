package ccsuite.base.model;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.experimental.Accessors;
import jakarta.xml.bind.annotation.*;
import ccsuite.base.util.EnvironmentVariableResolver;

/**
 * Shared credential model used across all modules.
 * Maps to CCAPICredential tag in CCAPIsCredentials.xml
 */
@Data
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY, getterVisibility = JsonAutoDetect.Visibility.NONE, isGetterVisibility = JsonAutoDetect.Visibility.NONE, setterVisibility = JsonAutoDetect.Visibility.NONE)
@JsonInclude(JsonInclude.Include.NON_NULL)
@Accessors(fluent = true, chain = true)
@XmlAccessorType(XmlAccessType.FIELD)
public class CCAPICredential {

    @XmlAttribute(name = "ccapiBaseUrl", required = true)
    private String ccapiBaseUrl;

    @XmlAttribute(name = "id", required = true)
    private String id;

    @XmlAttribute(name = "name", required = true)
    private String name;

    @XmlAttribute(name = "email", required = true)
    private String email;

    @XmlAttribute(name = "orgId", required = true)
    private String orgId;

    @XmlAttribute(name = "key", required = true)
    private String key;

    @XmlAttribute(name = "active", required = false)
    private Boolean active;

    @XmlAttribute(name = "trackUsage", required = false)
    private Boolean trackUsage;

    @XmlAttribute(name = "ping", required = false)
    private Boolean ping;

    @XmlAttribute(name = "tier", required = false)
    private Integer tier;

    @XmlAttribute(name = "ua", required = false)
    private String ua;

    @XmlAttribute(name = "trackChats", required = false)
    private Boolean trackChats;

    @XmlAttribute(name = "claudecodelimit", required = false)
    private String claudecodelimit;

    @XmlAttribute(name = "claudecodeactive", required = false)
    private Boolean claudecodeactive;

    @XmlAttribute(name = "watchOnly", required = false)
    private Boolean watchOnly;

    @XmlAttribute(name = "primary", required = false)
    private Boolean primary;

    /**
     * The owner's own account, used by nobody else. The watcher then never holds back on it for the sake of another
     * person: no 5-hour guard, and in the last day of its week it is used up to 100% (other accounts only up to their
     * {@link #claudecodelimit()}), since a week's quota that is not used is lost at the reset. Defaults to false.
     */
    public boolean primary() {
        return primary != null ? primary : false;
    }

    /**
     * Monitor only: the account is tracked (usage shown, counted in the totals) but never swapped to for
     * Claude Code and never used to serve the local proxy, e.g. a reserve kept for an emergency.
     * Defaults to false.
     */
    public boolean watchOnly() {
        return watchOnly != null ? watchOnly : false;
    }

    /**
     * Get active with default value of true if not set
     * (credentials are active by default for backwards compatibility)
     */
    public boolean active() {
        return active != null ? active : true;
    }

    /**
     * Get trackUsage with default value of false if not set
     */
    public boolean trackUsage() {
        return trackUsage != null ? trackUsage : false;
    }

    /**
     * Get ping with default value of false if not set
     */
    public boolean ping() {
        return ping != null ? ping : false;
    }

    /**
     * Get tier with default value of 1 if not set
     */
    public int tier() {
        return tier != null ? tier : 1;
    }

    /**
     * Get trackChats with default value of false if not set
     */
    public boolean trackChats() {
        return trackChats != null ? trackChats : false;
    }

    /**
     * Soft limit (e.g. "95%") past which Claude Code usage should warn / prompt a
     * swap, leaving the remainder as reserve. Defaults to "95%" when unset.
     */
    public String claudecodelimit() {
        return claudecodelimit != null ? claudecodelimit : "95%";
    }

    /**
     * True when this is (one of) the account(s) Claude Code is currently active on.
     * Written by claudeswap on switch; the usage tracker lists active accounts first.
     * Defaults to false. Multiple accounts may be active in theory.
     */
    public boolean claudecodeactive() {
        return claudecodeactive != null ? claudecodeactive : false;
    }

    /**
     * Parsed numeric percent of {@link #claudecodelimit()} (e.g. 95.0). Falls back
     * to 95.0 when the value is malformed.
     */
    public double claudeCodeLimitPercent() {
        var s = claudecodelimit().trim();
        if (s.endsWith("%")) s = s.substring(0, s.length() - 1).trim();
        try { return Double.parseDouble(s); } catch (NumberFormatException e) { return 95.0; }
    }

    /**
     * Get the resolved ccapiBaseUrl with environment variable substitution.
     * Supports Windows-style environment variables like %CCAPI_BASE_URL%
     *
     * @return the ccapiBaseUrl with environment variables resolved
     */
    public String resolvedCcapiBaseUrl() {
        return EnvironmentVariableResolver.resolve(ccapiBaseUrl);
    }
}
