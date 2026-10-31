package ccsuite.ccacc;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import ccsuite.ccacc.Control.*;

/** The command line's end of {@link Control}. Empty means no ccacc is listening, and the command should do its work itself. */
final class ControlClient {

    static final int CONNECT_MS = 700;
    /** A switch may refresh a token and ask Anthropic who owns it, so the app is given time to answer. */
    static final int ANSWER_MS = 120_000;

    static Optional<Reply> send(Path file, List<String> args) {
        Endpoint end;
        try {
            if (!Files.exists(file)) return Optional.empty();
            end = Control.JSON.readValue(file.toFile(), Endpoint.class);
        } catch (IOException e) {
            return Optional.empty();
        }
        var s = new Socket();
        try {
            s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), end.port()), CONNECT_MS);
        } catch (IOException e) {
            closeQuietly(s);
            return Optional.empty();   // nobody is listening: a stale file from an app that is gone
        }
        // From here the app has the command. Never fall back to doing it ourselves: it may well have run.
        try (s) {
            s.setSoTimeout(ANSWER_MS);
            var out = s.getOutputStream();
            out.write((Control.JSON.writeValueAsString(new Request(end.token(), args)) + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            var line = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8)).readLine();
            if (line == null) return Optional.of(Reply.error("ccacc closed the connection without an answer; check its state with: ccacc status"));
            return Optional.of(Control.JSON.readValue(line, Reply.class));
        } catch (IOException e) {
            return Optional.of(Reply.error("ccacc did not answer (" + e.getMessage() + "); check its state with: ccacc status"));
        }
    }

    private static void closeQuietly(Socket s) {
        try { s.close(); } catch (IOException ignored) { }
    }

    private ControlClient() {}
}
