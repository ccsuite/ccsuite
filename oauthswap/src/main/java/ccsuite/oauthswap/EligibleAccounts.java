package ccsuite.oauthswap;

import ccsuite.base.model.*;

import java.util.*;

/**
 * Selects the CCAPIsCredentials entries eligible for Claude Code rotation:
 * {@code active && trackUsage && tier >= 1} (tier 0 = free accounts are excluded).
 * Source order is preserved so rotation is deterministic.
 */
public final class EligibleAccounts {

    private EligibleAccounts() {}

    public static List<CCAPICredential> eligible(CCAPIsCredentials creds) {
        return creds.credentials().stream()
                .filter(c -> c.active() && c.trackUsage() && c.tier() >= 1)
                .toList();
    }

    /** The accounts Claude Code and the local proxy may be served from: eligible, and not monitor-only. */
    public static List<CCAPICredential> servable(CCAPIsCredentials creds) {
        return eligible(creds).stream().filter(c -> !c.watchOnly()).toList();
    }

    public static Optional<CCAPICredential> byId(CCAPIsCredentials creds, String id) {
        return creds.credentials().stream().filter(c -> id.equals(c.id())).findFirst();
    }

    public static List<CCAPICredential> matchByEmail(List<CCAPICredential> list, String email) {
        if (email == null || email.isBlank()) return List.of();
        return list.stream().filter(c -> email.equalsIgnoreCase(c.email())).toList();
    }
}
