package ccsuite.usagetracker.ui;

import ccsuite.base.model.CCAPIsCredentials;

/** Marks the account Claude Code is really logged into, so the list and the card put it first whatever an old file says. */
final class LiveActive {

    /** Exactly the account with this id is marked; null (Claude Code is on no managed account) marks none. */
    static void apply(CCAPIsCredentials credentials, String activeId) {
        credentials.credentials().forEach(c -> c.claudecodeactive(activeId != null && activeId.equals(c.id())));
    }

    private LiveActive() {}
}
