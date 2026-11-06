package ccsuite.ccgate;

import module java.base;
import ccsuite.quotaweights.QuotaWeights;
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
    final QuotaWeights weights;
    final Activity activity = new Activity();
    Admission admission;
    PurposeCheck purpose;
    HttpServer server;

    public GateServer(int port, GrantBook book, Policy policy, Forwarder forwarder, Ledger ledger, QuotaWeights weights) {
        this.port = port; this.book = book; this.policy = policy; this.forwarder = forwarder; this.ledger = ledger; this.weights = weights;
    }

    /** Who may say yes to a caller with an unknown key; without one such a call is refused at once. */
    public GateServer approver(Approver approver) {
        admission = approver == null ? null : new Admission(approver, book, Admission.WAIT);
        return this;
    }

    /** The after-the-fact purpose check for person grants; null for none. */
    public GateServer purpose(PurposeCheck p) {
        purpose = p;
        return this;
    }

    public Activity activity() {
        return activity;
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
            serve(ex);
        }
    }

    /** The catch is inside the exchange's scope: an error answers with the gate's own 502, not with an empty reply. */
    void serve(HttpExchange ex) {
        try {
            var raw = ex.getRequestBody().readAllBytes();
            var messages = ex.getRequestURI().getPath().endsWith("/v1/messages") && ex.getRequestMethod().equals("POST");
            var body = messages ? BodyRewrite.parse(raw) : null;
            var call = identify(ex, body);
            if (call.grant() == null && call.key() != null && admission != null) {
                var admitted = admission.admit(new ApprovalRequest(call.keyFingerprint(), ex.getRequestHeaders().getFirst("user-agent"),
                        call.session(), call.modelAsked(), call.family(), call.remoteUser() != null, Instant.now()));
                if (admitted.grant() == null) {
                    refuse(ex, call, admitted.refusal());
                    return;
                }
                call.grant(admitted.grant()).keyRef(admitted.grant().key(call.keyFingerprint()).orElse(null));
            }
            if (call.grant() != null) activity.begin(call);
            try {
                var refusal = policy.check(call, Instant.now());
                if (refusal.isPresent()) {
                    refuse(ex, call, refusal.get());
                    return;
                }
                if (body != null) {
                    BodyRewrite.prepare(body, call.grant());
                    var note = purpose == null ? null : purpose.noteFor(call);
                    if (note != null) BodyRewrite.addNote(body, note);
                }
                forwarder.forward(ex, call, body, raw);
                if (purpose != null) purpose.after(call, body);
            } finally {
                if (call.grant() != null) activity.end(call);
            }
        } catch (Exception e) {
            try { Replies.error(ex, 502, "gate error: " + e.getMessage()); } catch (Exception ignored) { }
        }
    }

    void refuse(HttpExchange ex, Call call, Refusal refusal) throws IOException {
        activity.refused(call);
        ledger.append(Forwarder.entry(call, null, 0).outcome("refused").status(refusal.status()).reason(refusal.reason()));
        Replies.error(ex, refusal.status(), refusal.reason());
    }

    Call identify(HttpExchange ex, ObjectNode body) {
        var h = ex.getRequestHeaders();
        var key = Keys.fromHeaders(h);
        var fp = key == null ? null : Keys.fingerprint(key);
        var grant = fp == null ? null : book.current().byKey(fp).orElse(null);
        var model = body == null ? null : body.path("model").asText(null);
        return new Call().key(key).keyFingerprint(fp).grant(grant).keyRef(grant == null ? null : grant.key(fp).orElse(null))
                .remoteUser(h.getFirst("x-ccgate-relay") != null ? "relay" : null).session(h.getFirst("x-claude-code-session-id")).agent(h.getFirst("x-claude-code-agent-id"))
                .project(h.getFirst("x-ccgate-project")).job(h.getFirst("x-ccgate-job")).modelAsked(model)
                .family(model == null ? "none" : weights.family(model)).stream(body != null && body.path("stream").asBoolean(false));
    }
}
