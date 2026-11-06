package ccsuite.base.config;

import ccsuite.base.model.CCAPICredential;
import ccsuite.base.model.CCAPIsCredentials;

import java.util.List;
import java.util.Map;

/** Adds, removes and changes accounts in a loaded {@link CCAPIsCredentials}; the caller saves. Properties are named as in the file. */
public final class AccountEditor {

    /** What {@link #set} accepts. The id is the account's name in every other file and cannot be changed. */
    public static final List<String> PROPERTIES = List.of("name", "email", "ccapiBaseUrl", "orgId", "key", "active",
            "trackUsage", "ping", "tier", "ua", "trackChats", "claudecodelimit", "watchOnly", "primary");

    /** The base URL every account carries unless told otherwise: the environment variable the other accounts use. */
    public static final String DEFAULT_BASE_URL = "%CCAPI_BASE_URL%";

    /** A new account from {@code id}, {@code name} and {@code email} (required) and any other property; the id must be new. */
    public static CCAPICredential add(CCAPIsCredentials all, Map<String, String> props) {
        var id = props.get("id");
        if (id == null || id.isBlank()) throw new IllegalArgumentException("an account needs an id");
        if (all.getById(id) != null) throw new IllegalArgumentException("there is already an account with id " + id);
        for (var need : List.of("name", "email"))
            if (props.get(need) == null || props.get(need).isBlank()) throw new IllegalArgumentException("an account needs a " + need);
        var c = new CCAPICredential().id(id);
        // Without a base URL the usage widget skips the account and shows "no data".
        c.ccapiBaseUrl(DEFAULT_BASE_URL);
        props.forEach((k, v) -> { if (!k.equals("id")) apply(c, k, v); });
        if (c.ccapiBaseUrl() == null) c.ccapiBaseUrl(DEFAULT_BASE_URL);
        all.credentials().add(c);
        return c;
    }

    public static void set(CCAPIsCredentials all, String id, String property, String value) {
        var c = all.getById(id);
        if (c == null) throw new IllegalArgumentException("no account with id " + id);
        apply(c, property, value);
    }

    public static CCAPICredential remove(CCAPIsCredentials all, String id) {
        var c = all.getById(id);
        if (c == null) throw new IllegalArgumentException("no account with id " + id);
        all.credentials().remove(c);
        return c;
    }

    /** One line about an account; the session key is never shown, only whether there is one. */
    public static String describe(CCAPICredential c) {
        return "%-10s %-22s %s  active=%s trackUsage=%s monitorOnly=%s primary=%s ping=%s tier=%d limit=%s key=%s".formatted(c.id(), c.name(), c.email(), c.active(),
                c.trackUsage(), c.watchOnly(), c.primary(), c.ping(), c.tier(), c.claudecodelimit(),
                c.key() == null || c.key().isBlank() ? "none" : "set");
    }

    static void apply(CCAPICredential c, String property, String value) {
        var v = value == null || value.isBlank() ? null : value.trim();
        switch (property) {
            case "name" -> c.name(v);
            case "email" -> c.email(v);
            case "ccapiBaseUrl" -> c.ccapiBaseUrl(v);
            case "orgId" -> c.orgId(v);
            case "key" -> c.key(v);
            case "ua" -> c.ua(v);
            case "claudecodelimit" -> c.claudecodelimit(limit(v));
            case "watchOnly" -> c.watchOnly(flag(property, v));
            case "primary" -> c.primary(flag(property, v));
            case "active" -> c.active(flag(property, v));
            case "trackUsage" -> c.trackUsage(flag(property, v));
            case "ping" -> c.ping(flag(property, v));
            case "trackChats" -> c.trackChats(flag(property, v));
            case "tier" -> c.tier(number(v));
            default -> throw new IllegalArgumentException("unknown property " + property + "; known: " + String.join(", ", PROPERTIES));
        }
    }

    static Boolean flag(String property, String v) {
        if (v == null) return null;
        return switch (v.toLowerCase()) {
            case "true", "on", "yes", "1" -> true;
            case "false", "off", "no", "0" -> false;
            default -> throw new IllegalArgumentException(property + " takes on or off, not " + v);
        };
    }

    /** A limit is a percent from 1 to 100, written with or without the sign; stored as, say, 90%. Empty clears it (the default 95% applies). */
    static String limit(String v) {
        if (v == null) return null;
        try {
            var n = Double.parseDouble(v.endsWith("%") ? v.substring(0, v.length() - 1).trim() : v);
            if (n < 1 || n > 100) throw new NumberFormatException();
            return (n == Math.rint(n) ? String.valueOf((long) n) : String.valueOf(n)) + "%";
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("the limit is a percent from 1 to 100, not " + v);
        }
    }

    static Integer number(String v) {
        if (v == null) return null;
        try { return Integer.valueOf(v); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("tier takes a whole number, not " + v); }
    }

    private AccountEditor() {}
}
