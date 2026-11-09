package ccsuite.base.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Paid and free are shown apart, and trackUsage never moves a free account into the paid group. */
class PaidFreeSplitTest {

    static CCAPICredential account(String id, Integer tier, boolean track) { return new CCAPICredential().id(id).tier(tier).trackUsage(track); }

    @Test void aFreeAccountWithTrackUsageOnIsNotInThePaidList() {
        var all = new CCAPIsCredentials();
        all.credentials().addAll(List.of(account("P1", 5, true), account("F1", 0, true), account("P2", null, true), account("P3", 1, false)));
        assertEquals(List.of("P1", "P2"), all.getPaidUsageCredentials().stream().map(CCAPICredential::id).toList());
        assertEquals(List.of("P1", "F1", "P2"), all.getTrackUsageCredentials().stream().map(CCAPICredential::id).toList(), "the unfiltered list is unchanged for other users of it");
    }

    @Test void theOrderedPaidListPutsClaudeCodesAccountFirstAndStaysPaidOnly() {
        var all = new CCAPIsCredentials();
        var active = account("P2", 2, true).claudecodeactive(true);
        all.credentials().addAll(List.of(account("F1", 0, true), account("P1", 5, true), active));
        assertEquals(List.of("P2", "P1"), all.getPaidUsageCredentialsOrdered().stream().map(CCAPICredential::id).toList());
    }
}
