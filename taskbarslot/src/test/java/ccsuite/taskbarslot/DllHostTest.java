package ccsuite.taskbarslot;

import module java.base;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Builds the slot DLL for the scratch registry keys and drives it with a fake rebar site (native/hosttest.c): the band lengths for 1 to 16 cards, its class and text, and the relayout message. No Explorer, no real keys. */
class DllHostTest {

    @Test void theDllMakesTheBandAsLongAsLegacyPlusRegisteredCardsAndRelaysOutOnRequest(@TempDir Path work) throws Exception {
        assumeTrue(System.getProperty("os.name").startsWith("Windows") && onPath("gcc.exe"), "needs Windows and gcc");
        var script = Path.of("native", "hosttest.cmd").toAbsolutePath();
        var p = new ProcessBuilder("cmd.exe", "/c", script.toString(), work.toString()).redirectErrorStream(true).start();
        var out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, p.waitFor(), out);
        assertTrue(out.contains("ALL CHECKS PASSED"), out);
        assertTrue(out.contains("legacy 2 + 3 registered: class CcuSlotWnd5"), out);
    }

    static boolean onPath(String exe) {
        return Arrays.stream(System.getenv("PATH").split(File.pathSeparator)).anyMatch(d -> Files.exists(Path.of(d, exe)));
    }
}
