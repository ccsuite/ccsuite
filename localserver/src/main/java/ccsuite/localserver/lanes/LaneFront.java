package ccsuite.localserver.lanes;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * One port, one lane. {@code pclaude} is the PAID lane: every path and every model goes to the paid accounts as it is. {@code fclaude} is the FREE lane: Haiku and
 * Sonnet go to the free accounts, nothing else is accepted (opus and the like are refused with a 400 saying so, never sent to another lane). There is no mixing and no
 * moving between lanes: a session lives on one lane, so its prompt cache lives in one place. Every request is logged with its account and handed to {@code sink} (the window).
 */
public final class LaneFront {

    private static final Logger log = LoggerFactory.getLogger(LaneFront.class);

    final String name;
    final int port;
    final Lane lane;
    /** True for the free port: only haiku and sonnet, and only /v1/messages and /v1/models. */
    final boolean limited;
    final Consumer<Routed> sink;
    HttpServer server;

    public LaneFront(String name, int port, Lane lane, boolean limited, Consumer<Routed> sink) {
        this.name = name; this.port = port; this.lane = lane; this.limited = limited; this.sink = sink;
    }

    public String name() { return name; }
    public int port() { return port; }
    /** The port actually listening (differs from {@link #port()} only when that is 0). */
    public int boundPort() { return server == null ? 0 : server.getAddress().getPort(); }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        log.info("{} on http://127.0.0.1:{} -> {} lane", name, port, lane.name());
    }

    public void stop() { if (server != null) server.stop(0); server = null; }

    void handle(HttpExchange ex) {
        try (ex) {
            var path = ex.getRequestURI().getPath();
            var body = ex.getRequestBody().readAllBytes();
            if (!limited || path.startsWith("/v1/messages")) serve(ex, body);
            else if (path.equals("/v1/models")) Replies.send(ex, 200, Replies.MODELS);
            else Replies.send(ex, 404, Replies.error(404, name + " serves /v1/messages and /v1/models only, not " + path));
        } catch (Exception e) {
            try { Replies.send(ex, 502, Replies.error(502, name + ": " + e)); } catch (Exception ignored) { }
        }
    }

    void serve(HttpExchange ex, byte[] body) throws Exception {
        var t0 = System.nanoTime();
        var model = Replies.modelOf(body);
        if (limited && ModelClass.of(model) == ModelClass.OTHER) {
            var why = name + " serves haiku and sonnet only; no route for model " + model;
            Replies.send(ex, 400, Replies.error(400, why));
            record(model, "refused", why, 400, t0, null, ex);
            return;
        }
        if (!lane.available()) {
            var why = "the " + lane.name() + " lane is not available";
            Replies.send(ex, 503, Replies.error(503, why));
            record(model, "refused", why, 503, t0, null, ex);
            return;
        }
        var r = lane.send(ex, body);
        record(model, lane.name(), "", r.status(), t0, r.account(), ex);
    }

    void record(String model, String laneName, String reason, int status, long t0, String account, HttpExchange ex) {
        var r = new Routed(Instant.now(), name + ":" + port, model == null ? "-" : model, laneName, reason, status, (System.nanoTime() - t0) / 1_000_000, account, ex.getRequestHeaders().getFirst("x-ccacc-job"));
        log.info(r.line());
        sink.accept(r);
    }
}
