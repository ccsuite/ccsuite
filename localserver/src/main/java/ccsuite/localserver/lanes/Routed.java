package ccsuite.localserver.lanes;

import java.time.Instant;

/** One request and how it ended, for the log line, the window and the tests. {@code lane} is the lane that answered or "refused"; {@code account} the account that served it when known; {@code job} the client's {@code x-ccacc-job} tag. */
public record Routed(Instant at, String port, String model, String lane, String reason, int status, long millis, String account, String job) {
    public String line() {
        return port + " " + model + " -> " + lane + (account == null ? "" : " [" + account + "]") + " " + status + " " + millis + "ms" + (reason.isEmpty() ? "" : "  (" + reason + ")");
    }
}
