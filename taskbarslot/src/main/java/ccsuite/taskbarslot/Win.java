package ccsuite.taskbarslot;

import module java.base;
import ccsuite.taskbarslot.win32.*;
import static ccsuite.taskbarslot.win32.User32.*;

/** The few Win32 calls the slot host needs, over the FFM bindings of {@code win32}. */
public final class Win {
    private Win() {}

    static long bits(int... v) {
        var r = 0L;
        for (var x : v) r |= Integer.toUnsignedLong(x);
        return r;
    }

    public static final long
        CLEAR = bits(WS_POPUP(), WS_CAPTION(), WS_THICKFRAME(), WS_SYSMENU(), WS_MINIMIZEBOX(), WS_MAXIMIZEBOX()),
        CHILD = bits(WS_CHILD(), WS_VISIBLE(), WS_CLIPSIBLINGS());
    public static final int SHOW = SWP_NOACTIVATE() | SWP_FRAMECHANGED() | SWP_SHOWWINDOW();

    static MemorySegment str(Arena a, String s) { return s == null ? MemorySegment.NULL : a.allocateFrom(s, StandardCharsets.UTF_16LE); }

    public static MemorySegment findWindow(String cls, String title) {
        try (var a = Arena.ofConfined()) { return FindWindowW(str(a, cls), str(a, title)); }
    }

    public static MemorySegment findChild(MemorySegment parent, String cls) { return findChild(parent, cls, null); }

    public static MemorySegment findChild(MemorySegment parent, String cls, String title) {
        try (var a = Arena.ofConfined()) { return FindWindowExW(parent, MemorySegment.NULL, str(a, cls), str(a, title)); }
    }

    public static int[] rect(MemorySegment h, boolean client) {
        try (var a = Arena.ofConfined()) {
            var r = tagRECT.allocate(a);
            if (client) GetClientRect(h, r); else GetWindowRect(h, r);
            return new int[]{tagRECT.left(r), tagRECT.top(r), tagRECT.right(r), tagRECT.bottom(r)};
        }
    }

    public static boolean same(MemorySegment a, MemorySegment b) { return a.address() == b.address(); }
    public static boolean none(MemorySegment h) { return h.address() == 0; }
}
