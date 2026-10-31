package ccsuite.oauthswap;

import ccsuite.base.model.CCAPICredential;
import ccsuite.base.model.CCAPIsCredentials;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EligibleAccountsTest {

    static CCAPICredential acct(String id, boolean watchOnly) {
        return new CCAPICredential().id(id).trackUsage(true).tier(1).watchOnly(watchOnly);
    }

    @Test void aMonitorOnlyAccountIsWatchedButNeverServedFrom() {
        var all = new CCAPIsCredentials();
        all.credentials().addAll(List.of(acct("main", false), acct("reserve", true), new CCAPICredential().id("free").trackUsage(true).tier(0)));
        assertEquals(List.of("main", "reserve"), EligibleAccounts.eligible(all).stream().map(CCAPICredential::id).toList());
        assertEquals(List.of("main"), EligibleAccounts.servable(all).stream().map(CCAPICredential::id).toList());
    }
}
