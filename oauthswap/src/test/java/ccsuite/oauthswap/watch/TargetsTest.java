package ccsuite.oauthswap.watch;

import ccsuite.oauthclient.AccountUsage;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class TargetsTest {

    @Test void monitorOnlyAccountsAreNotTargetsButTheActiveOneStays() {
        var u = new LinkedHashMap<String, AccountUsage>();
        for (var id : new String[]{"active", "main", "reserve"}) u.put(id, new AccountUsage(10.0, null, 10.0, null, 10.0));
        var servable = Set.of("main");
        assertEquals(java.util.List.of("active", "main"), java.util.List.copyOf(Targets.of(u, "active", servable::contains).keySet()));
        assertEquals(java.util.List.of("main"), java.util.List.copyOf(Targets.of(u, null, servable::contains).keySet()));
    }
}
