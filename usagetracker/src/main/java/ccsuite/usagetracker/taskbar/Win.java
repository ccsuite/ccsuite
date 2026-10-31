package ccsuite.usagetracker.taskbar;

import module java.base;
import ccsuite.usagetracker.taskbar.win32.*;
import static ccsuite.usagetracker.taskbar.win32.User32.*;

final class Win {
    static long bits(int... v) {
        var r = 0L;
        for (var x : v) r |= Integer.toUnsignedLong(x);
        return r;
    }

    static final long
        CLEAR = bits(WS_POPUP(), WS_CAPTION(), WS_THICKFRAME(), WS_SYSMENU(), WS_MINIMIZEBOX(), WS_MAXIMIZEBOX()),
        CHILD = bits(WS_CHILD(), WS_VISIBLE(), WS_CLIPSIBLINGS());
    static final int SHOW = SWP_NOACTIVATE() | SWP_FRAMECHANGED() | SWP_SHOWWINDOW();

    static MemorySegment str(Arena a, String s) { return s == null ? MemorySegment.NULL : a.allocateFrom(s, StandardCharsets.UTF_16LE); }

    static MemorySegment findWindow(String cls, String title) {
        try (var a = Arena.ofConfined()) { return FindWindowW(str(a, cls), str(a, title)); }
    }

    static MemorySegment findChild(MemorySegment parent, String cls) {
        try (var a = Arena.ofConfined()) { return FindWindowExW(parent, MemorySegment.NULL, str(a, cls), MemorySegment.NULL); }
    }

    static int[] rect(MemorySegment h, boolean client) {
        try (var a = Arena.ofConfined()) {
            var r = tagRECT.allocate(a);
            if (client) GetClientRect(h, r); else GetWindowRect(h, r);
            return new int[]{tagRECT.left(r), tagRECT.top(r), tagRECT.right(r), tagRECT.bottom(r)};
        }
    }

    static boolean same(MemorySegment a, MemorySegment b) { return a.address() == b.address(); }
    static boolean none(MemorySegment h) { return h.address() == 0; }
}
