package ccsuite.ccgate;

import module java.base;

/** The gate's folder, ~/ccsuite/ccgate, holding grants.json and the ledger. */
public final class GateHome {

    public static Path dir() {
        return Path.of(System.getProperty("user.home"), "ccsuite", "ccgate");
    }

    public static Path ledger() {
        return dir().resolve("ledger");
    }

    private GateHome() { }
}
