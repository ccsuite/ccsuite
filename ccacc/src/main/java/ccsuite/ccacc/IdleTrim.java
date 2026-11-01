package ccsuite.ccacc;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.time.Duration;

/**
 * Gives memory back while nobody uses the app. After {@link #QUIET} without a {@link #touch} (a menu opened, a window shown,
 * a switch, a command from the CLI) it collects once and, on Windows, asks the system to move the process's pages out of RAM
 * ({@code SetProcessWorkingSetSize(-1, -1)}); they come back in milliseconds when something reads them. Measured on the live app:
 * 219 MB in RAM, 12 MB right after the trim, about 65 MB a few minutes later with the card and the poll still running.
 * <p>
 * Unlike an app that does nothing, this one polls every minute and draws the card, so a trim repeats while it stays quiet,
 * at most once per {@link #REPEAT}; a single trim would let the poll refill the memory for good.
 */
final class IdleTrim {

    static final Duration QUIET = Duration.ofMinutes(2), REPEAT = Duration.ofMinutes(4), CHECK = Duration.ofSeconds(30);

    private static volatile long lastActivity = System.nanoTime();
    private static volatile long lastTrim;

    static void touch() { lastActivity = System.nanoTime(); }

    static void start() {
        Thread.ofPlatform().name("ccacc-idle-trim").daemon().start(() -> {
            while (true) {
                try {
                    Thread.sleep(CHECK.toMillis());
                } catch (InterruptedException e) {
                    return;
                }
                var now = System.nanoTime();
                if (due(now, lastActivity, lastTrim)) {
                    trim();
                    lastTrim = System.nanoTime();
                }
            }
        });
    }

    /** Quiet for {@link #QUIET}, and the last trim (if any) at least {@link #REPEAT} ago. */
    static boolean due(long now, long lastActivity, long lastTrim) {
        if (now - lastActivity < QUIET.toNanos()) return false;
        return lastTrim == 0 || now - lastTrim >= REPEAT.toNanos();
    }

    static void trim() {
        System.gc();
        if (EMPTY == null) return;
        try {
            var unused = (int) EMPTY.invoke(CURRENT.invoke(), -1L, -1L);
        } catch (Throwable ignored) {
            // A trim that cannot be done is only a missed saving.
        }
    }

    private static final MethodHandle CURRENT, EMPTY;

    static {
        MethodHandle current = null, empty = null;
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            try {
                var linker = Linker.nativeLinker();
                var kernel = SymbolLookup.libraryLookup("kernel32.dll", Arena.global());
                current = linker.downcallHandle(kernel.find("GetCurrentProcess").orElseThrow(), FunctionDescriptor.of(ValueLayout.ADDRESS));
                empty = linker.downcallHandle(kernel.find("SetProcessWorkingSetSize").orElseThrow(),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));
            } catch (Throwable e) {
                current = null;
                empty = null;
            }
        }
        CURRENT = current;
        EMPTY = empty;
    }

    private IdleTrim() {}
}
