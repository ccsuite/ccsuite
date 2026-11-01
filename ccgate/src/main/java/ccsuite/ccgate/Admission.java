package ccsuite.ccgate;

import module java.base;

/**
 * What happens to a call whose key the gate does not know: the owner is asked once, however many calls arrive meanwhile
 * (a batch sends dozens at once), and a decline is remembered for a while so a retrying script does not open window after
 * window. An approval becomes a grant holding that very key, written to grants.json like any other.
 */
final class Admission {

    static final Duration WAIT = Duration.ofSeconds(60), DECLINED_FOR = Duration.ofMinutes(10);

    /** Either a grant to go on with, or the sentence that refuses the call. */
    record Result(Grant grant, Refusal refusal) { }

    final Approver approver;
    final GrantBook book;
    final Duration wait;
    final Map<String, CompletableFuture<Result>> pending = new ConcurrentHashMap<>();
    final Map<String, Instant> declined = new ConcurrentHashMap<>();

    Admission(Approver approver, GrantBook book, Duration wait) {
        this.approver = approver; this.book = book; this.wait = wait;
    }

    Result admit(ApprovalRequest r) {
        var until = declined.get(r.fingerprint());
        if (until != null && Instant.now().isBefore(until))
            return refused(403, "The provider declined this key a moment ago. Ask the provider for a grant.");
        var mine = new CompletableFuture<Result>();
        var theirs = pending.putIfAbsent(r.fingerprint(), mine);
        if (theirs != null) {
            try {
                return theirs.get(wait.toSeconds() + 5, TimeUnit.SECONDS);
            } catch (Exception e) {
                return waiting();
            }
        }
        try {
            var result = decide(r);
            mine.complete(result);
            return result;
        } finally {
            pending.remove(r.fingerprint());
        }
    }

    Result decide(ApprovalRequest r) {
        try {
            var approval = approver.ask(r, wait);
            if (approval.isEmpty()) {
                declined.put(r.fingerprint(), Instant.now().plus(DECLINED_FOR));
                return refused(403, "The provider declined this request.");
            }
            return new Result(grantFor(r, approval.get()), null);
        } catch (TimeoutException e) {
            return waiting();
        } catch (Exception e) {
            return refused(502, "Could not record the approval: " + e.getMessage());
        }
    }

    Grant grantFor(ApprovalRequest r, Approval a) throws IOException {
        synchronized (book) {
            var grants = book.current();
            var existing = grants.byKey(r.fingerprint());
            if (existing.isPresent()) return existing.get();
            var now = Instant.now();
            var expires = now.plus(Duration.ofDays(a.days())).toString();
            var id = "ask-" + r.fingerprint().substring(0, 8);
            var who = r.agent() == null ? "an unnamed program" : r.agent();
            var grant = new Grant().id(id).name(who).kind("tool").purpose("approved at the window for " + who)
                    .models(new ArrayList<>(a.models())).daily(new LinkedHashMap<>(a.daily())).created(now.toString()).expires(expires);
            grant.keys().add(new KeyRef().fingerprint(r.fingerprint()).label("approved at the window").issued(now.toString()).expires(expires));
            grants.grants().add(grant);
            book.save(grants);
            return grant;
        }
    }

    static Result waiting() {
        return refused(401, "Waiting for approval from the provider. Retry in a minute.");
    }

    static Result refused(int status, String reason) {
        return new Result(null, Refusal.of(status, reason));
    }
}
