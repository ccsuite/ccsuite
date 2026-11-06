package ccsuite.localserver.lanes;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** A lane for tests: answers with its own name, or with {@code failStatus} when set, and remembers what it was sent. */
final class FakeLane implements Lane {
    final String name;
    final List<String> got = new ArrayList<>();
    volatile int failStatus;
    volatile boolean available = true;

    FakeLane(String name) { this.name = name; }

    @Override public String name() { return name; }
    @Override public boolean available() { return available; }
    @Override public boolean hasHeadroom() { return true; }

    @Override public Result send(HttpExchange ex, byte[] body) throws IOException {
        got.add(new String(body, StandardCharsets.UTF_8));
        if (failStatus != 0) {
            Replies.send(ex, failStatus, Replies.error(failStatus, name + " failed"));
            return Result.done(failStatus);
        }
        var b = ("answered by " + name).getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(200, b.length);
        ex.getResponseBody().write(b);
        return Result.done(200, name.toUpperCase());
    }
}
