package ccsuite.ccacc.trim;

import module java.base;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * PRP 89 end to end, on a headless claude.exe that each test starts itself. The clock is fake (a look every 30 s of fake time, run back to back), the readings are the real ones.
 * Every look is restricted to the pid of the test's own process: a test never trims, or even lists for trimming, any other claude.exe.
 */
class TrimRealProcessTest {

    final FakeProbe.Clk clock = new FakeProbe.Clk();
    final TrimState state = new TrimState();
    final Trimmer trimmer = new Trimmer(new WinProbe(), clock, () -> false, TabsInfo::new);

    static void windowsWithClaude() { assumeTrue(Win.available() && OwnClaude.onPath().isPresent(), "needs Windows and claude.exe on the PATH"); }

    List<Outcome> looks(TrimSettings cfg, OwnClaude c, int count) {
        var all = new ArrayList<Outcome>();
        for (var i = 0; i < count; i++) { clock.sec(30); all.addAll(trimmer.tick(cfg, state, c.only())); }
        return all;
    }

    @Test void anIdleSessionLosesItsWorkingSetAndKeepsItsPrivateBytes() throws Exception {
        windowsWithClaude();
        try (var c = OwnClaude.start()) {
            c.settle();
            var cfg = new TrimSettings().enabled(true).idleMinutes(5).minWorkingSetMb(80);
            var before = c.seen();
            assertTrue(before.workingSet() > 80L * 1_048_576, "the headless claude.exe holds more than 80 MB: " + before.workingSet());
            assertEquals(0, looks(cfg, c, 1).size());
            assertEquals(0, looks(cfg, c, 8).size(), "not idle for five minutes yet");
            var done = looks(cfg, c, 2);
            assertEquals(1, done.size(), "trimmed once, after the five minutes");
            var o = done.get(0);
            assertEquals(c.pid(), o.pid());
            System.out.printf("trim result: ws %.1f -> %.1f MB, private %.1f -> %.1f MB%n", TrimLog.mb(o.wsBefore()), TrimLog.mb(o.wsAfter()), TrimLog.mb(o.privBefore()), TrimLog.mb(o.privAfter()));
            assertTrue(o.wsAfter() < o.wsBefore() / 4.0, "working set fell to under a quarter");
            assertEquals(o.privBefore(), o.privAfter(), o.privBefore() * 0.10, "private bytes did not change by more than 10 percent");
            assertEquals(0, looks(new TrimSettings().enabled(true).minWorkingSetMb(1), c, 10).size(), "no second trim within the ten minute gap");
        }
    }

    @Test void aSessionThatIsAnsweringIsLeftAloneUntilItIsIdleAgain() throws Exception {
        windowsWithClaude();
        try (var c = OwnClaude.start()) {
            c.settle();
            var cfg = new TrimSettings().enabled(true).idleMinutes(2).minWorkingSetMb(80);
            assertEquals(0, looks(cfg, c, 4).size());
            c.say("Count from 1 to 30, one number per line, nothing else.");
            assumeTrue(c.waitForResult(90), "the model did not answer in time (no network or no login)");
            assertEquals(0, looks(cfg, c, 1).size(), "the look right after the turn finds it busy");
            assertTrue(c.seen().workingSet() > 80L * 1_048_576, "still not trimmed");
            assertEquals(0, looks(cfg, c, 3).size(), "two idle minutes have not passed since the turn");
            assertEquals(1, looks(cfg, c, 2).size(), "idle again for two minutes: trimmed");
        }
    }

    @Test void aProcessThatIsNotClaudeIsNeverListedNorTrimmed() throws Exception {
        assumeTrue(Win.available());
        var other = new ProcessBuilder("ping", "-n", "40", "127.0.0.1").redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        try {
            Thread.sleep(500);
            var probe = new WinProbe();
            assertTrue(probe.claudes().stream().noneMatch(p -> p.pid() == other.pid()), "ping.exe is not listed");
            assertFalse(WinProbe.isClaude(ProcessHandle.of(other.pid()).orElseThrow()));
            assertTrue(trimmer.force(other.pid(), state).isEmpty(), "a manual trim of it is refused");
            assertNull(probe.trim(new Proc().pid(other.pid()).startMs(1)), "and so is a direct one");
        } finally {
            other.destroyForcibly();
        }
    }
}
