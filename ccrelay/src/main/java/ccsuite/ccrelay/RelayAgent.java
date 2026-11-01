package ccsuite.ccrelay;

import module java.base;
import module java.net.http;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The owner's side of the relay, run inside ccacc: a few workers each keep one request open to the relay's
 * {@code /_agent/next}, take the call that arrives, send it to the local gate exactly as a local caller would (marked
 * as coming through the relay), and stream the gate's answer back. All connections go outwards; after sleep or a
 * network change a worker simply tries again, waiting longer each time up to half a minute.
 */
public final class RelayAgent implements AutoCloseable {

    static final ObjectMapper JSON = new ObjectMapper();
    /** Headers the JDK client sets itself and refuses to be given. */
    static final Set<String> RESTRICTED = Set.of("connection", "content-length", "expect", "host", "upgrade", "transfer-encoding");
    /** Answer headers worth passing back to the remote caller. */
    static final Predicate<String> PASS_BACK = k -> k.equals("content-type") || k.equals("request-id") || k.equals("retry-after")
            || k.startsWith("anthropic-") || k.startsWith("x-ccgate-");

    final URI relay;
    final String secret;
    final URI gate;
    final int workers;
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
    final List<Thread> threads = new ArrayList<>();
    volatile boolean running;
    volatile String problem;
    volatile Instant connectedAt;

    public RelayAgent(URI relay, String secret, URI gate, int workers) {
        this.relay = relay;
        this.secret = secret;
        this.gate = gate;
        this.workers = workers;
    }

    public synchronized RelayAgent start() {
        if (running) return this;
        running = true;
        for (var i = 0; i < workers; i++) threads.add(Thread.ofVirtual().name("ccrelay-agent-" + i).start(this::work));
        return this;
    }

    @Override public synchronized void close() {
        running = false;
        threads.forEach(Thread::interrupt);
        threads.clear();
    }

    public boolean running() {
        return running;
    }

    /** Why the last attempt to reach the relay failed; null while it works. */
    public String problem() {
        return problem;
    }

    void work() {
        var backoff = 1;
        while (running) {
            try {
                var r = http.send(HttpRequest.newBuilder(relay.resolve("/_agent/next")).timeout(Duration.ofSeconds(60))
                        .header("authorization", "Bearer " + secret).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
                if (r.statusCode() == 401) throw new IOException("the relay does not accept this provider secret");
                if (r.statusCode() != 200 && r.statusCode() != 204) throw new IOException("the relay answered " + r.statusCode());
                problem = null;
                if (connectedAt == null) connectedAt = Instant.now();
                backoff = 1;
                if (r.statusCode() == 200) serve(JSON.readTree(r.body()));
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                problem = e.getMessage() == null ? e.toString() : e.getMessage();
                connectedAt = null;
                try { Thread.sleep(backoff * 1000L); } catch (InterruptedException ie) { return; }
                backoff = Math.min(backoff * 2, 30);
            }
        }
    }

    /** One call from the relay: to the local gate, and its answer back as it streams. */
    void serve(JsonNode env) throws IOException, InterruptedException {
        var id = env.path("id").asText();
        var b = HttpRequest.newBuilder(gate.resolve(env.path("path").asText("/"))).timeout(Duration.ofMinutes(10));
        env.path("headers").properties().forEach(e -> {
            if (!RESTRICTED.contains(e.getKey())) b.header(e.getKey(), e.getValue().asText());
        });
        var body = Base64.getDecoder().decode(env.path("body").asText(""));
        var method = env.path("method").asText("POST");
        b.method(method, body.length == 0 && !method.equals("POST") ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        HttpResponse<InputStream> answer;
        try {
            answer = http.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException gateDown) {
            var msg = JSON.writeValueAsBytes(Map.of("type", "error", "error", Map.of("type", "api_error",
                    "message", "ccrelay: the provider's gate is not running")));
            send(id, 503, Map.of("content-type", "application/json"), new ByteArrayInputStream(msg));
            return;
        }
        var headers = new LinkedHashMap<String, String>();
        answer.headers().map().forEach((k, vs) -> { var lk = k.toLowerCase(Locale.ROOT); if (PASS_BACK.test(lk) && !vs.isEmpty()) headers.put(lk, vs.get(0)); });
        try (var in = answer.body()) {
            send(id, answer.statusCode(), headers, in);
        }
    }

    void send(String id, int status, Map<String, String> headers, InputStream body) throws IOException, InterruptedException {
        var encoded = Base64.getEncoder().encodeToString(JSON.writeValueAsBytes(headers));
        http.send(HttpRequest.newBuilder(relay.resolve("/_agent/reply/" + id)).timeout(Duration.ofMinutes(11))
                .header("authorization", "Bearer " + secret).header("x-relay-status", String.valueOf(status))
                .header("x-relay-headers", encoded)
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> body)).build(), HttpResponse.BodyHandlers.discarding());
    }
}
