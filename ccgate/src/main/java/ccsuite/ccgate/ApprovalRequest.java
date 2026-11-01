package ccsuite.ccgate;

import module java.base;

/** What the gate can tell the owner about a caller it does not know. Never the content of the request. */
public record ApprovalRequest(String fingerprint, String agent, String session, String model, String family, Instant at) {

    /** The first characters of the key's fingerprint: enough to recognise it in `ccacc gate grant list`. */
    public String shortFingerprint() {
        return fingerprint.substring(0, 12);
    }
}
