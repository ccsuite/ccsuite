package ccsuite.ccgate;

import module java.base;
import module jdk.httpserver;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The gate's endpoint on 127.0.0.1: identifies the caller by its key, checks the grant, makes the request ready and
 * hands it to the forwarder. A refused call is answered at once and written to the ledger; nothing goes out.
 */
public final class GateServer {

    final int port;
    final GrantBook book;
    final Policy policy;
    final Forwarder forwarder;
    final Ledger ledger;
    final Pricing pricing;
    HttpServer server;

    public GateServer(int port, GrantBook book, Policy policy, Forwarder forwarder, Ledger ledger, Pricing pricing) {
        this.port = port; this.book = book; this.policy = policy; this.forwarder = forwarder; this.ledger = ledger; this.pricing = pricing;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    public void stop() {
        if (server != null) server.stop(0);
    }

    void handle(HttpExchange ex) {
        try (ex) {
            var raw = ex.getRequestBody().readAllBytes();
            var messages = ex.getRequestURI().getPath().endsWith("/v1/messages") && ex.getRequestMethod().equals("POST");
            var body = messages ? BodyRewrite.parse(raw) : null;
            var call = identify(ex, body);
            var refusal = policy.check(call, Instant.now());
            if (refusal.isPresent()) {
                ledger.append(Forwarder.entry(call, null, 0).outcome("refused").status(refusal.get().status()).reason(refusal.get().reason()));
                Replies.error(ex, refusal.get().status(), refusal.get().reason());
                return;
            }
            if (body != null) BodyRewrite.prepare(body, call.grant());
            forwarder.forward(ex, call, body, raw);
        } catch (Exception e) {
            try { Replies.error(ex, 502, "gate error: " + e.getMessage()); } catch (Exception ignored) { }
        }
    }

    Call identify(HttpExchange ex, ObjectNode body) {
        var h = ex.getRequestHeaders();
        var key = Keys.fromHeaders(h);
        var fp = key == null ? null : Keys.fingerprint(key);
        var grant = fp == null ? null : book.current().byKey(fp).orElse(null);
        var model = body == null ? null : body.path("model").asText(null);
        return new Call().key(key).keyFingerprint(fp).grant(grant).keyRef(grant == null ? null : grant.key(fp).orElse(null))
                .session(h.getFirst("x-claude-code-session-id")).agent(h.getFirst("x-claude-code-agent-id"))
                .project(h.getFirst("x-ccgate-project")).job(h.getFirst("x-ccgate-job")).modelAsked(model)
                .family(model == null ? "none" : pricing.family(model)).stream(body != null && body.path("stream").asBoolean(false));
    }
}
