package ccsuite.oauthclient;

/** One-line reason a usage fetch failed — distinguishes transient (rate-limit/5xx) from
 *  anything else, so a failure doesn't collapse into an undifferentiated "unknown". */
public final class UsageErrors {

    private UsageErrors() {}

    public static String terse(Throwable e) {
        if (e instanceof UsageHttpException h)
            return h.status == 429 ? "rate-limited (HTTP 429) — transient, try again shortly" : "HTTP " + h.status;
        return String.valueOf(e.getMessage());
    }
}
