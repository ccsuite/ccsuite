package ccsuite.ccacc;

import ccsuite.base.config.SafeFiles;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Function;

import ccsuite.ccacc.Control.*;

/** The app's end of {@link Control}: loopback only, a token read from a file only this user can read, one request per connection. */
final class ControlServer implements AutoCloseable {

    static final int MAX_LINE = 64 * 1024;

    private final ServerSocket socket;
    private final String token;
    private final Path file;
    private final Function<List<String>, Reply> handler;

    private ControlServer(ServerSocket socket, String token, Path file, Function<List<String>, Reply> handler) {
        this.socket = socket;
        this.token = token;
        this.file = file;
        this.handler = handler;
    }

    static ControlServer start(Path file, Function<List<String>, Reply> handler) throws IOException {
        var socket = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
        var bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        var token = HexFormat.of().formatHex(bytes);
        var server = new ControlServer(socket, token, file, handler);
        Files.createDirectories(file.getParent());
        var tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Control.JSON.writeValue(tmp.toFile(), new Endpoint(socket.getLocalPort(), token, ProcessHandle.current().pid()));
        SafeFiles.replace(tmp, file);
        Thread.ofPlatform().daemon().name("ccacc-control").start(server::accept);
        return server;
    }

    int port() { return socket.getLocalPort(); }

    private void accept() {
        while (!socket.isClosed()) {
            try {
                var conn = socket.accept();
                Thread.ofVirtual().name("ccacc-control-conn").start(() -> serve(conn));
            } catch (IOException e) {
                if (!socket.isClosed()) System.err.println("ccacc: control channel: " + e);
            }
        }
    }

    private void serve(Socket conn) {
        try (conn) {
            conn.setSoTimeout(30_000);
            var in = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
            var line = readLine(in);
            Reply reply;
            try {
                var req = Control.JSON.readValue(line, Request.class);
                reply = token.equals(req.token()) && req.args() != null && !req.args().isEmpty()
                        ? handler.apply(req.args()) : Reply.error("refused");
            } catch (Exception e) {
                reply = Reply.error("ccacc: " + (e.getMessage() == null ? e.toString() : e.getMessage()));
            }
            var out = conn.getOutputStream();
            out.write((Control.JSON.writeValueAsString(reply) + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (IOException e) {
            // the command line went away; nothing to answer
        }
    }

    private static String readLine(BufferedReader in) throws IOException {
        var sb = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0 && c != '\n') {
            if (sb.length() >= MAX_LINE) throw new IOException("request too long");
            sb.append((char) c);
        }
        return sb.toString();
    }

    @Override public void close() {
        try { socket.close(); } catch (IOException ignored) { }
        try {
            // only remove the file if it is still ours; a newer app may have written its own
            var current = Control.JSON.readValue(file.toFile(), Endpoint.class);
            if (current.token().equals(token)) Files.deleteIfExists(file);
        } catch (IOException ignored) { }
    }
}
