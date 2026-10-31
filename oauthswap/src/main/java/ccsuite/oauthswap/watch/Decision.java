package ccsuite.oauthswap.watch;

/** What the watcher should do this cycle, with the target account (if any) and a human reason. */
public record Decision(Kind kind, String targetId, String reason) {

    public enum Kind { NONE, PROMPT, AUTO_SWITCH }

    public static Decision none(String reason)              { return new Decision(Kind.NONE, null, reason); }
    public static Decision prompt(String id, String reason) { return new Decision(Kind.PROMPT, id, reason); }
    public static Decision auto(String id, String reason)   { return new Decision(Kind.AUTO_SWITCH, id, reason); }
}
