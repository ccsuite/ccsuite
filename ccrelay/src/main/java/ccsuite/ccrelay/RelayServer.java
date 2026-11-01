package ccsuite.ccrelay;

import module java.base;
import module jdk.httpserver;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The relay: remote callers send ordinary Messages API requests here; the owner's ccacc keeps a few requests open to
 * {@code /_agent/next} (outwards, so the owner's machine needs no open port), takes each waiting call, answers it from
 * its own gate and streams the answer back through {@code /_agent/reply/<id>}, chunk by chunk.
 * <p>
 * The relay decides nothing: no accounts, no grants, no ledger. It refuses a call that carries no key at all (cheap,
 * and nothing anonymous travels on), and answers 503 "provider offline" when no agent picks a call up in time. The
 * caller's key goes through untouched; the gate on the owner's machine applies the grant, the approval and the budget.
 * The agent proves itself with a shared secret, of which the relay keeps only the SHA-256.
 */
public final class RelayServer {

    static final ObjectMapper JSON = new ObjectMapper();
    static final Set<String> HOP = Set.of("host", "content-length", "connection", "transfer-encoding", "keep-alive", "te", "upgrade",
            "proxy-connection", "expect", "accept-encoding");

    /** One caller's request, waiting for the agent and then for the answer. */
    static final class Pending {
        final String id = UUID.randomUUID().toString();
        final String method, path;
        final Map<String, String> headers;
        final byte[] body;
        final CompletableFuture<Boolean> picked = new CompletableFuture<>();
        final CompletableFuture<Reply> reply = new CompletableFuture<>();

        Pending(String method, String path, Map<String, String> headers, byte[] body) {
            this.method = method; this.path = path; this.headers = headers; this.body = body;
        }
    }

    /** The agent's answer as it streams in; {@code done} lets the agent's request end once the caller has it all. */
    record Reply(int status, Map<String, String> headers, InputStream body, CompletableFuture<Void> done) { }

    final int port;
    final byte[] secretHash;
    final Duration pickWait, answerWait, poll;
    final BlockingQueue<Pending> queue = new LinkedBlockingQueue<>();
    final Map<String, Pending> taken = new ConcurrentHashMap<>();
    final java.util.concurrent.atomic.AtomicInteger openPolls = new java.util.concurrent.atomic.AtomicInteger();
    HttpServer server;

    public RelayServer(int port, String secretHashHex, Duration pickWait, Duration answerWait, Duration poll) {
        this.port = port;
        this.secretHash = HexFormat.of().parseHex(secretHashHex.strip());
        this.pickWait = pickWait; this.answerWait = answerWait; this.poll = poll;
    }

    public static RelayServer standard(int port, String secretHashHex) {
        return new RelayServer(port, secretHashHex, Duration.ofSeconds(15), Duration.ofMinutes(10), Duration.ofSeconds(25));
    }

    /** Binds to 127.0.0.1: the relay is reached through the web server in front of it, never directly. */
    public RelayServer start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        return this;
    }

    public void stop() {
        if (server != null) server.stop(0);
    }

    public int port() {
        return server == null ? port : server.getAddress().getPort();
    }

    void handle(HttpExchange ex) {
        try (ex) {
            var path = ex.getRequestURI().getPath();
            try {
                if (path.equals("/_agent/next")) next(ex);
                else if (path.startsWith("/_agent/reply/")) reply(ex, path.substring("/_agent/reply/".length()));
                else if (path.equals("/healthz")) text(ex, 200, online() ? "ok: provider online" : "ok: provider offline");
                else call(ex);
            } catch (Exception e) {
                try { error(ex, 502, "relay error: " + e.getMessage()); } catch (Exception ignored) { }
            }
        }
    }

    /** Online while the agent holds a poll open; a vanished agent drops off when its last open poll ends (at most one poll interval). */
    boolean online() {
        return openPolls.get() > 0;
    }

    // ---- remote callers

    void call(HttpExchange ex) throws Exception {
        var h = ex.getRequestHeaders();
        var key = h.getFirst("x-api-key");
        var auth = h.getFirst("authorization");
        if ((key == null || key.isBlank()) && (auth == null || auth.isBlank())) {
            error(ex, 401, "This relay needs a key in x-api-key. Ask the provider for one.");
            return;
        }
        var headers = new LinkedHashMap<String, String>();
        h.forEach((k, vs) -> { if (!HOP.contains(k.toLowerCase(Locale.ROOT)) && !vs.isEmpty()) headers.put(k.toLowerCase(Locale.ROOT), vs.get(0)); });
        headers.put("x-ccgate-relay", "1");
        var p = new Pending(ex.getRequestMethod(), ex.getRequestURI().toString(), headers, ex.getRequestBody().readAllBytes());
        queue.add(p);
        try {
            p.picked.get(pickWait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException notPicked) {
            if (queue.remove(p)) {
                error(ex, 503, "Provider offline: the owner's machine is not connected to the relay. Try again later.");
                return;
            }
            // taken by the agent at the very last moment: go on waiting for its answer
        }
        Reply r;
        try {
            r = p.reply.get(answerWait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException slow) {
            taken.remove(p.id);
            error(ex, 504, "The provider took too long to answer.");
            return;
        }
        try {
            r.headers().forEach((k, v) -> ex.getResponseHeaders().set(k, v));
            ex.sendResponseHeaders(r.status(), 0);
            try (var out = ex.getResponseBody()) {
                var buf = new byte[8192];
                for (int n; (n = r.body().read(buf)) > 0; ) {
                    out.write(buf, 0, n);
                    out.flush();
                }
            }
        } finally {
            r.done().complete(null);
        }
    }

    // ---- the agent (the owner's ccacc)

    boolean agent(HttpExchange ex) throws IOException {
        var auth = ex.getRequestHeaders().getFirst("authorization");
        var secret = auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7) ? auth.substring(7).strip() : "";
        if (MessageDigest.isEqual(sha256(secret), secretHash)) return true;
        error(ex, 401, "not the provider");
        return false;
    }

    void next(HttpExchange ex) throws Exception {
        if (!agent(ex)) return;
        Pending p;
        openPolls.incrementAndGet();
        try {
            p = queue.poll(poll.toMillis(), TimeUnit.MILLISECONDS);
        } finally {
            openPolls.decrementAndGet();
        }
        if (p == null) {
            ex.sendResponseHeaders(204, -1);
            return;
        }
        taken.put(p.id, p);
        p.picked.complete(true);
        var envelope = new LinkedHashMap<String, Object>();
        envelope.put("id", p.id);
        envelope.put("method", p.method);
        envelope.put("path", p.path);
        envelope.put("headers", p.headers);
        envelope.put("body", Base64.getEncoder().encodeToString(p.body));
        var bytes = JSON.writeValueAsBytes(envelope);
        ex.getResponseHeaders().set("content-type", "application/json");
        ex.sendResponseHeaders(200, bytes.length);
        try (var out = ex.getResponseBody()) { out.write(bytes); }
    }

    @SuppressWarnings("unchecked")
    void reply(HttpExchange ex, String id) throws Exception {
        if (!agent(ex)) return;
        var p = taken.remove(id);
        if (p == null) {
            error(ex, 404, "no caller is waiting for " + id);
            return;
        }
        var h = ex.getRequestHeaders();
        var status = Integer.parseInt(Objects.requireNonNullElse(h.getFirst("x-relay-status"), "502"));
        var json = h.getFirst("x-relay-headers");
        Map<String, String> headers = json == null ? Map.of() : JSON.readValue(Base64.getDecoder().decode(json), Map.class);
        var done = new CompletableFuture<Void>();
        p.reply.complete(new Reply(status, headers, ex.getRequestBody(), done));
        try {
            done.get(answerWait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException ignored) { }
        ex.sendResponseHeaders(204, -1);
    }

    // ---- small things

    static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String hashHex(String secret) {
        return HexFormat.of().formatHex(sha256(secret));
    }

    static void error(HttpExchange ex, int status, String message) throws IOException {
        var type = switch (status) {
            case 401 -> "authentication_error";
            case 503 -> "overloaded_error";
            default -> "api_error";
        };
        var bytes = JSON.writeValueAsBytes(Map.of("type", "error", "error", Map.of("type", type, "message", "ccrelay: " + message)));
        ex.getResponseHeaders().set("content-type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (var out = ex.getResponseBody()) { out.write(bytes); }
    }

    static void text(HttpExchange ex, int status, String s) throws IOException {
        var bytes = s.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("content-type", "text/plain; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (var out = ex.getResponseBody()) { out.write(bytes); }
    }
}
