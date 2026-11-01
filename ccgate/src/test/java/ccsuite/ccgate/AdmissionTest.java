package ccsuite.ccgate;

import module java.base;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class AdmissionTest {

    @TempDir Path dir;

    ApprovalRequest request(String key) {
        return new ApprovalRequest(Keys.fingerprint(key), "example-tool/1.0", null, "claude-haiku-5-5", "haiku", false, Instant.now());
    }

    @Test
    void approvalBecomesAGrantHoldingThatKey() {
        var book = new GrantBook(dir.resolve("grants.json"));
        var admission = new Admission((r, wait) -> Optional.of(Approval.of(1, 7)), book, Duration.ofSeconds(5));
        var result = admission.admit(request("some-key"));
        assertNull(result.refusal());
        assertEquals(List.of("haiku", "sonnet"), result.grant().models());
        assertEquals(30.0, result.grant().daily().get("sonnet"));
        var again = new GrantBook(dir.resolve("grants.json")).current().byKey(Keys.fingerprint("some-key"));
        assertTrue(again.isPresent(), "the grant is on disk, found by the key's fingerprint");
        assertTrue(again.get().keys().get(0).expires() != null);
    }

    @Test
    void aDeclineIsRememberedSoTheOwnerIsNotAskedAgain() {
        var asked = new AtomicInteger();
        var admission = new Admission((r, wait) -> { asked.incrementAndGet(); return Optional.empty(); },
                new GrantBook(dir.resolve("grants.json")), Duration.ofSeconds(5));
        assertEquals(403, admission.admit(request("k")).refusal().status());
        assertEquals(403, admission.admit(request("k")).refusal().status());
        assertEquals(1, asked.get());
    }

    @Test
    void noAnswerIsWaitingNotADecline() {
        var asked = new AtomicInteger();
        var admission = new Admission((r, wait) -> { asked.incrementAndGet(); throw new TimeoutException(); },
                new GrantBook(dir.resolve("grants.json")), Duration.ofSeconds(5));
        assertEquals(401, admission.admit(request("k")).refusal().status());
        assertTrue(admission.admit(request("k")).refusal().reason().contains("Waiting for approval"));
        assertEquals(2, asked.get(), "a timeout is not remembered: the next call asks again");
    }

    @Test
    void manyCallsAtOnceAskOnce() throws Exception {
        var asked = new AtomicInteger();
        var release = new CountDownLatch(1);
        var admission = new Admission((r, wait) -> {
            asked.incrementAndGet();
            try { release.await(); } catch (InterruptedException e) { throw new IllegalStateException(e); }
            return Optional.of(Approval.of(0, 1));
        }, new GrantBook(dir.resolve("grants.json")), Duration.ofSeconds(5));
        try (var pool = Executors.newFixedThreadPool(6)) {
            var results = new ArrayList<Future<Admission.Result>>();
            for (var i = 0; i < 6; i++) results.add(pool.submit(() -> admission.admit(request("batch"))));
            while (asked.get() == 0) Thread.sleep(10);
            Thread.sleep(100);
            release.countDown();
            for (var f : results) assertNull(f.get(10, TimeUnit.SECONDS).refusal());
        }
        assertEquals(1, asked.get());
        assertEquals(1, new GrantBook(dir.resolve("grants.json")).current().grants().size());
    }
}
