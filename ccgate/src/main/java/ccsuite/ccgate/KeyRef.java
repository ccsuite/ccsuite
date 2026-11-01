package ccsuite.ccgate;

import datapotter.datahelper.Data;

/** One key of a grant. Only its SHA-256 fingerprint is kept; the key itself is shown once, when issued. */
@Data
public final class KeyRef extends KeyRef_A {
    String fingerprint;
    String label;
    String issued;
    String expires;
    boolean revoked;
}
