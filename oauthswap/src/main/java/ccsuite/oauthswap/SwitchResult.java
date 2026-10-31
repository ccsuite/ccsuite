package ccsuite.oauthswap;

/** Outcome of a switch: who we came from, who we landed on. {@code fromId} may be null. */
public record SwitchResult(String fromId, String toId, String toEmail) {
    public boolean changed() { return !toId.equals(fromId); }
}
