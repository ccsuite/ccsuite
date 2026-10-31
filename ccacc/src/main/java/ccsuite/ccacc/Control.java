package ccsuite.ccacc;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Path;

/**
 * How a ccacc command line talks to the resident ccacc. The app listens on a loopback port and writes the port and a
 * random token into {@code ~/ccsuite/ccacc/control.json}; a command that changes something connects, sends the line
 * and prints the answer, so the running app is the only writer of everything it holds in memory. With no app running
 * the connection fails and the command does its work itself.
 */
final class Control {

    static final ObjectMapper JSON = new ObjectMapper();

    /** A request is one line of JSON: {@code {"token":"…","args":["switch","BOB"]}}. */
    record Request(String token, java.util.List<String> args) {}

    /** The answer is one line of JSON; {@code message} is what the command line prints. */
    record Reply(boolean ok, String message) {
        static Reply ok(String message) { return new Reply(true, message); }
        static Reply error(String message) { return new Reply(false, message); }
    }

    /** What the app writes so a command line can find it. */
    record Endpoint(int port, String token, long pid) {}

    /** Commands that change state and so go to the running app when there is one. The rest read and run alone. */
    static final java.util.Set<String> STATE_CHANGING = java.util.Set.of("switch", "register", "mode", "part", "account");

    static boolean changesState(String[] args) { return args.length > 0 && STATE_CHANGING.contains(args[0]); }

    static Path defaultFile() { return Path.of(System.getProperty("user.home"), "ccsuite", "ccacc", "control.json"); }

    private Control() {}
}
