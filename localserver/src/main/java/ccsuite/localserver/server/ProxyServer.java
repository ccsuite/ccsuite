package ccsuite.localserver.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.Executors;

/**
 * The classic local proxy (ccls): every path and method verbatim to {@code api.anthropic.com} through the {@link PaidLane}, one port, no model routing.
 * The ports with modes (pclaude, fclaude, pfclaude) are {@link ccsuite.localserver.lanes.LaneFront}s over the same lane.
 */
public final class ProxyServer {

    private static final Logger log = LoggerFactory.getLogger(ProxyServer.class);

    private final PaidLane lane;
    private final int port;
    private HttpServer server;

    public ProxyServer(AccountRouter router, int port) {
        this.lane = new PaidLane(router); this.port = port;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        log.info("cclocalserver proxy: http://127.0.0.1:{} -> {}", port, PaidLane.API);
    }

    public void stop() { if (server != null) server.stop(0); }

    private void handle(HttpExchange ex) {
        try (ex) {
            lane.send(ex, ex.getRequestBody().readAllBytes());
        } catch (Exception e) {
            try { PaidLane.respond(ex, 502, "proxy error: " + e); } catch (Exception ignore) {}
        }
    }
}
