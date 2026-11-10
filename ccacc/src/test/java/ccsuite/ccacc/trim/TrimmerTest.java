package ccsuite.ccacc.trim;

import module java.base;
import org.junit.jupiter.api.Test;

import static ccsuite.ccacc.trim.FakeProbe.proc;
import static org.junit.jupiter.api.Assertions.*;

/** The idle decision with fake clocks and fake process readings (PRP 89). */
class TrimmerTest {

    final FakeProbe probe = new FakeProbe();
    final FakeProbe.Clk clock = new FakeProbe.Clk();
    final TrimSettings cfg = new TrimSettings().enabled(true);
    final TrimState state = new TrimState();
    boolean lane;
    TabsInfo tabs = new TabsInfo();
    final Trimmer trimmer = new Trimmer(probe, clock, () -> lane, () -> tabs);

    /** Looks every 30 s for the given number of seconds; returns the trims done. */
    int run(int seconds, Predicate<Proc> only) {
        var n = 0;
        for (var t = 0; t < seconds; t += 30) { clock.sec(30); n += trimmer.tick(cfg, state, only).size(); }
        return n;
    }

    int run(int seconds) { return run(seconds, p -> true); }

    @Test void idleProcessIsTrimmedOnceAfterFiveMinutes() {
        probe.add(proc(1, 200));
        trimmer.tick(cfg, state, p -> true);
        assertEquals(0, run(240));
        assertEquals(1, run(60));
        assertEquals(List.of(1L), probe.trimmed);
        assertTrue(state.today(clock.millis()) > 190);
    }

    @Test void cpuMovingIsBusyAndNeverTrimmed() {
        probe.add(proc(1, 200));
        var n = 0;
        for (var i = 0; i < 40; i++) { probe.procs.get(0).cpuMs(probe.procs.get(0).cpuMs() + 3000); clock.sec(30); n += trimmer.tick(cfg, state, p -> true).size(); }
        assertEquals(0, n);
    }

    @Test void idleNoiseUnderTheLimitIsStillIdle() {
        probe.add(proc(1, 200));
        var n = 0;
        for (var i = 0; i < 20; i++) { probe.procs.get(0).cpuMs(probe.procs.get(0).cpuMs() + 400); clock.sec(30); n += trimmer.tick(cfg, state, p -> true).size(); }
        assertEquals(1, n);
    }

    @Test void readsOrWritesMeanBusy() {
        probe.add(proc(1, 200));
        var n = 0;
        for (var i = 0; i < 40; i++) { probe.procs.get(0).ioOps(10 + i); clock.sec(30); n += trimmer.tick(cfg, state, p -> true).size(); }
        assertEquals(0, n);
    }

    @Test void aChangeInChildrenMeansBusy() {
        probe.add(proc(1, 200));
        var n = 0;
        for (var i = 0; i < 40; i++) { probe.procs.get(0).descendants(i % 2); clock.sec(30); n += trimmer.tick(cfg, state, p -> true).size(); }
        assertEquals(0, n);
    }

    @Test void aCallInFlightOnALaneHoldsEverythingBack() {
        probe.add(proc(1, 200));
        trimmer.tick(cfg, state, p -> true);
        run(270);
        lane = true;
        assertEquals(0, run(30));
        lane = false;
        assertEquals(0, run(240));
        assertEquals(1, run(60));
    }

    @Test void aGapSinceTheLastLookIsNotIdle() {
        probe.add(proc(1, 200));
        trimmer.tick(cfg, state, p -> true);
        clock.sec(3600);
        assertEquals(0, trimmer.tick(cfg, state, p -> true).size());
    }

    @Test void smallWorkingSetIsLeftAlone() {
        probe.add(proc(1, 60));
        assertEquals(0, run(900));
    }

    @Test void theSameProcessIsNotTrimmedAgainWithinTheGap() {
        probe.add(proc(1, 200));
        trimmer.tick(cfg, state, p -> true);
        assertEquals(1, run(300));
        probe.procs.get(0).workingSet(150L * 1_048_576);
        assertEquals(0, run(540));
        assertEquals(1, run(60));
        assertEquals(2, probe.trimmed.size());
    }

    @Test void off_trimsNothing() {
        cfg.enabled(false);
        probe.add(proc(1, 200));
        assertEquals(0, run(900));
    }

    @Test void aProcessOutsideTheFilterIsNeverTouched() {
        probe.add(proc(1, 200)).add(proc(2, 200));
        trimmer.tick(cfg, state, p -> p.pid() == 1);
        run(600, p -> p.pid() == 1);
        assertEquals(List.of(1L), probe.trimmed);
    }

    @Test void onlyWhenFreeRamIsLow() {
        cfg.onlyBelowFreeGb(4);
        probe.add(proc(1, 200));
        trimmer.tick(cfg, state, p -> true);
        probe.free = 8L << 30;
        assertEquals(0, run(600));
        probe.free = 2L << 30;
        assertEquals(1, run(30));
    }

    @Test void theTabTheUserLooksAtIsHeldBack() {
        probe.add(proc(1, 200));
        probe.lineage.put(1L, Set.of(1L, 77L));
        tabs = new TabsInfo().hold(Set.of(77L));
        trimmer.tick(cfg, state, p -> true);
        assertEquals(0, run(900));
        tabs = new TabsInfo();
        assertEquals(1, run(30));
    }

    @Test void manualTrimOnlyReachesAListedClaude() {
        probe.add(proc(1, 200));
        assertTrue(trimmer.force(1, state).isPresent());
        assertTrue(trimmer.force(999, state).isEmpty());
        assertEquals(List.of(1L), probe.trimmed);
    }

    @Test void theLogLineCarriesPidSessionBeforeAfterAndToday() {
        probe.add(proc(1, 200));
        var line = TrimLog.line(trimmer.force(1, state).orElseThrow(), Instant.parse("2026-11-10T10:00:00Z"));
        assertTrue(line.contains("pid=1 session=s1 ws 200.0->2.0 MB private 400.0->400.0 MB given=198.0 MB today=198.0 MB"), line);
    }
}
