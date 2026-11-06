package ccsuite.usagetracker.taskbar;

import module java.base;
import static ccsuite.usagetracker.taskbar.win32.User32.*;

final class Slot {
    /** The band's window class: CcuSlotWnd2 is the two-card band the current DLL makes when ccacc asked for it, the others are one card long (an older DLL, or none asked). */
    static final String[] CLASSES = {"CcuSlotWnd2", "CcuSlotWnd", "CcuDeskBandWnd"};

    final String title;
    /** The free accounts' card (first in the band) or the usage card (after it, or alone). */
    final boolean free;
    MemorySegment band = MemorySegment.NULL, fx = MemorySegment.NULL;
    long fxBefore, bandBefore;
    boolean embedded;

    Slot(String title, boolean free) { this.title = title; this.free = free; }

    /** True on a vertical taskbar (taller than wide). */
    static boolean vertical() {
        var r = Win.rect(Win.findWindow("Shell_TrayWnd", null), false);
        return r[3] - r[1] > r[2] - r[0];
    }

    static MemorySegment findBand() {
        var rebar = Win.findChild(Win.findWindow("Shell_TrayWnd", null), "ReBarWindow32");
        for (var cls : CLASSES) {
            var b = Win.findChild(rebar, cls);
            if (!Win.none(b)) return b;
        }
        return MemorySegment.NULL;
    }

    /** How many cards long the band in the taskbar really is (2 only for the new DLL's two-card band): what Explorer has loaded, not what was asked for. */
    static int bandCards() {
        var rebar = Win.findChild(Win.findWindow("Shell_TrayWnd", null), "ReBarWindow32");
        return Win.none(Win.findChild(rebar, CLASSES[0])) ? 1 : 2;
    }

    /** How many cards share the band now: two only when the free card is on AND the band is the two-card one. */
    static int cards() { return SlotLayout.freeCard() && bandCards() == 2 ? 2 : 1; }

    boolean embed() {
        var f = Win.findWindow(null, title);
        var b = findBand();
        if (Win.none(f) || Win.none(b)) return false;
        if (free && bandCards() != 2) return false;   // an old slot DLL is still loaded: the band is one card long, so the free card waits (the usage card keeps the whole band)
        fx = f;
        band = b;
        fxBefore = GetWindowLongPtrW(fx, GWL_STYLE());
        bandBefore = GetWindowLongPtrW(band, GWL_STYLE());
        SetWindowLongPtrW(band, GWL_STYLE(), bandBefore | Integer.toUnsignedLong(WS_CLIPCHILDREN()));
        SetWindowPos(band, MemorySegment.NULL, 0, 0, 0, 0, SWP_NOMOVE() | SWP_NOSIZE() | SWP_NOZORDER() | SWP_NOACTIVATE() | SWP_FRAMECHANGED());
        SetWindowLongPtrW(fx, GWL_STYLE(), (fxBefore & ~Win.CLEAR) | Win.CHILD);
        SetParent(fx, band);
        embedded = true;
        place(true);
        return true;
    }

    void place(boolean force) {
        var c = Win.rect(band, true);
        var f = Win.rect(fx, true);
        var n = cards();
        var t = SlotLayout.target(c[2], c[3], vertical(), n, free ? 0 : n == 2 ? 1 : 0);
        if (!force && f[2] == t[2] && f[3] == t[3] && Win.rect(fx, false)[0] == Win.rect(band, false)[0] + t[0] && Win.rect(fx, false)[1] == Win.rect(band, false)[1] + t[1]) return;
        SetWindowPos(fx, MemorySegment.NULL, t[0], t[1], t[2], t[3], Win.SHOW);
        RedrawWindow(fx, MemorySegment.NULL, MemorySegment.NULL, RDW_INVALIDATE() | RDW_ERASE() | RDW_ALLCHILDREN() | RDW_UPDATENOW());
    }

    boolean alive() { return Win.same(GetParent(fx), band); }

    void release() {
        if (!embedded) return;
        embedded = false;
        SetParent(fx, MemorySegment.NULL);
        SetWindowLongPtrW(fx, GWL_STYLE(), fxBefore);
        SetWindowPos(fx, MemorySegment.NULL, -3000, 0, 0, 0, SWP_NOSIZE() | SWP_NOZORDER() | SWP_NOACTIVATE());
        SetWindowLongPtrW(band, GWL_STYLE(), bandBefore);
    }
}
