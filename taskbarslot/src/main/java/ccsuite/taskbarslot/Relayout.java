package ccsuite.taskbarslot;

import module java.base;
import static java.lang.foreign.ValueLayout.*;

/**
 * STRETCH, unproven on a real taskbar: tells the band that is loaded to read the registry again and ask Explorer for its size again, so a card registered or unregistered
 * takes effect without toggling the band or restarting Explorer. If Explorer ignores it, the fallback stays the same: toggle the band in the taskbar's Toolbars menu.
 * Two user32 calls bound by hand: they are not in the jextract subset of {@code win32}.
 */
public final class Relayout {
    static final Linker LINKER = Linker.nativeLinker();
    static final SymbolLookup USER32 = SymbolLookup.libraryLookup("user32.dll", Arena.global());
    static final MethodHandle REGISTER = LINKER.downcallHandle(USER32.find("RegisterWindowMessageW").orElseThrow(), FunctionDescriptor.of(JAVA_INT, ADDRESS));
    static final MethodHandle SEND = LINKER.downcallHandle(USER32.find("SendMessageTimeoutW").orElseThrow(), FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_INT, JAVA_LONG, JAVA_LONG, JAVA_INT, JAVA_INT, ADDRESS));
    static final int SMTO_ABORTIFHUNG = 2;

    private Relayout() {}

    /** True when a band answered (the DLL answers 1 once it has handled the message); false when there is no band, an older DLL that does not know the message, or Explorer did not answer in two seconds. */
    public static boolean send() {
        var band = Slot.findBand();
        if (Win.none(band)) return false;
        try (var a = Arena.ofConfined()) {
            var msg = (int) REGISTER.invokeExact(Win.str(a, "ccsuite.taskbar.relayout"));
            var result = a.allocate(JAVA_LONG);
            var ok = (long) SEND.invokeExact(band, msg, 0L, 0L, SMTO_ABORTIFHUNG, 2000, result);
            return ok != 0 && result.get(JAVA_LONG, 0) == 1;
        } catch (Throwable e) {
            return false;
        }
    }
}
