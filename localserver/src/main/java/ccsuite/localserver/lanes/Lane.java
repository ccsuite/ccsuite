package ccsuite.localserver.lanes;

import com.sun.net.httpserver.HttpExchange;

import java.util.OptionalDouble;

/** A way to answer a Messages request: the paid OAuth passthrough or the free web lane. Each port serves exactly one lane, so the answer of the lane always goes to the client as it is. */
public interface Lane {

    String name();

    /** Sends the request upstream and streams the answer to {@code ex}; whatever the upstream answers (also a refusal) goes back unchanged. */
    Result send(HttpExchange ex, byte[] body) throws Exception;

    /** Whether the lane can serve at all (it exists and has accounts). */
    boolean available();

    /** Whether some account in it has room right now. */
    boolean hasHeadroom();

    /** The lowest weekly pace of its accounts (100 = on pace to use the week exactly); empty when unknown or not applicable. */
    default OptionalDouble weeklyPace() { return OptionalDouble.empty(); }
}
