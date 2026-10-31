package ccsuite.oauthswap.watch;

import ccsuite.oauthclient.AccountUsage;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

/** The accounts a switch may go to: the usages of the monitor-only ones are left out, the active account stays (it is where Claude Code is). */
final class Targets {

    static Map<String, AccountUsage> of(Map<String, AccountUsage> usages, String activeId, Predicate<String> servable) {
        var out = new LinkedHashMap<String, AccountUsage>();
        usages.forEach((id, u) -> { if (id.equals(activeId) || servable.test(id)) out.put(id, u); });
        return out;
    }

    private Targets() {}
}
