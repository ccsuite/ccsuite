package ccsuite.ccgate;

import module java.base;
import ccsuite.quotaweights.QuotaWeights;
import module java.net.http;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;

/**
 * Sends one allowed call out: tries the accounts in order, moving on after a retryable answer, and writes one ledger
 * line per attempt, so a rate limit absorbed by the next account is still on record.
 */
public final class Forwarder {

    static final Set<Integer> RETRY = Set.of(401, 403, 429, 500, 502, 503, 529);

    final Accounts accounts;
    final Ledger ledger;
    final Spend spend;
    final QuotaWeights weights;

    public Forwarder(Accounts accounts, Ledger ledger, Spend spend, QuotaWeights weights) {
        this.accounts = accounts; this.ledger = ledger; this.spend = spend; this.weights = weights;
    }

    public void forward(HttpExchange ex, Call call, ObjectNode body, byte[] raw) throws IOException, InterruptedException {
        var ids = accounts.candidates();
        if (ids.isEmpty()) {
            ledger.append(entry(call, null, 0).outcome("refused").status(503).reason("no account has headroom"));
            Replies.error(ex, 503, "No account has headroom right now. Try again later.");
            return;
        }
        for (var i = 0; i < ids.size(); i++) {
            var id = ids.get(i);
            var token = accounts.token(id);
            if (token.isEmpty()) continue;
            var bytes = body == null ? raw : BodyRewrite.forAccount(body, accounts.uuid(id));
            var t0 = System.nanoTime();
            var r = Upstream.send(ex.getRequestMethod(), ex.getRequestURI().toString(), bytes, ex.getRequestHeaders(), token.get());
            var last = i == ids.size() - 1;
            if (RETRY.contains(r.statusCode()) && !last) {
                if (r.statusCode() == 401) accounts.failed401(id);
                ledger.append(readings(entry(call, id, i + 1), r).outcome("retry").status(r.statusCode()).ms(ms(t0)));
                r.body().close();
                continue;
            }
            accounts.served(id);
            pass(ex, call, id, i + 1, r, t0);
            return;
        }
        Replies.error(ex, 503, "No account could be used right now.");
    }

    void pass(HttpExchange ex, Call call, String id, int attempt, HttpResponse<InputStream> r, long t0) throws IOException {
        Replies.copyHeaders(r.headers().map(), ex);
        if (call.grant() != null) ex.getResponseHeaders().set("x-ccgate-grant", call.grant().id());
        ex.getResponseHeaders().set("x-ccgate-account", id);
        ex.sendResponseHeaders(r.statusCode(), 0);
        var e = readings(entry(call, id, attempt), r).status(r.statusCode());
        var bps = call.grant() == null ? 0 : call.grant().throttleBps();
        try (var in = r.body(); var raw = ex.getResponseBody(); OutputStream out = bps > 0 ? new ThrottledOutput(raw, bps) : raw) {
            var sse = r.headers().firstValue("content-type").orElse("").contains("event-stream");
            var a = UsageTee.pump(in, out, sse);
            var model = a.model() != null ? a.model() : call.modelAsked();
            var cost = a.usage().cost(weights, model);
            e.model(model).family(cost.family()).usage(a.usage()).messageId(a.messageId()).stopReason(a.stopReason())
                    .points(round(cost.points())).dollars(round(cost.dollars()));
            e.outcome(r.statusCode() / 100 == 2 ? "ok" : "error");
        } catch (IOException broken) {
            e.outcome("broken").reason(broken.getMessage());
            throw broken;
        } finally {
            e.ms(ms(t0));
            ledger.append(e);
            spend.add(e);
        }
    }

    static LedgerEntry entry(Call c, String account, int attempt) {
        return new LedgerEntry().t(Instant.now().toString()).grant(c.grant() == null ? null : c.grant().id())
                .key(c.keyFingerprint() == null ? null : c.keyFingerprint().substring(0, 12)).session(c.session()).agent(c.agent())
                .project(c.project()).job(c.job()).remoteUser(c.remoteUser()).modelAsked(c.modelAsked()).family(c.family())
                .account(account).attempt(attempt == 0 ? null : attempt);
    }

    static LedgerEntry readings(LedgerEntry e, HttpResponse<?> r) {
        var h = r.headers();
        return e.u5h(num(h.firstValue("anthropic-ratelimit-unified-5h-utilization"))).u7d(num(h.firstValue("anthropic-ratelimit-unified-7d-utilization")))
                .limitStatus(h.firstValue("anthropic-ratelimit-unified-status").orElse(null))
                .claim(h.firstValue("anthropic-ratelimit-unified-representative-claim").orElse(null))
                .retryAfter(h.firstValue("retry-after").orElse(null)).requestId(h.firstValue("request-id").orElse(null));
    }

    static Double num(Optional<String> s) {
        try {
            return s.map(v -> Double.valueOf(v.strip())).orElse(null);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static long ms(long t0) {
        return (System.nanoTime() - t0) / 1_000_000;
    }

    static double round(double v) {
        return Math.round(v * 10_000) / 10_000d;
    }
}
