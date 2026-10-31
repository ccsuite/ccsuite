package ccsuite.oauthclient;

import java.io.IOException;

/** A non-2xx response from the usage endpoint; carries the status so callers can 401-refresh. */
public final class UsageHttpException extends IOException {
    public final int status;

    public UsageHttpException(int status, String body) {
        super("usage HTTP " + status + (body == null || body.isBlank() ? "" : ": " + truncate(body)));
        this.status = status;
    }

    private static String truncate(String s) { return s.length() > 300 ? s.substring(0, 300) : s; }
}
