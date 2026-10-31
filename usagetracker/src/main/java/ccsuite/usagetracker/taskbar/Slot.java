package ccsuite.usagetracker.taskbar;

import module java.base;
import static ccsuite.usagetracker.taskbar.win32.User32.*;

final class Slot {
    static final String[] CLASSES = {"CcuSlotWnd", "CcuDeskBandWnd"};

    final String title;
    MemorySegment band = MemorySegment.NULL, fx = MemorySegment.NULL;
    long fxBefore, bandBefore;
    boolean embedded;

    Slot(String title) { this.title = title; }

    static MemorySegment findBand() {
        var rebar = Win.findChild(Win.findWindow("Shell_TrayWnd", null), "ReBarWindow32");
        for (var cls : CLASSES) {
            var b = Win.findChild(rebar, cls);
            if (!Win.none(b)) return b;
        }
        return MemorySegment.NULL;
    }

    boolean embed() {
        var f = Win.findWindow(null, title);
        var b = findBand();
        if (Win.none(f) || Win.none(b)) return false;
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
        if (!force && f[2] == c[2] && f[3] == c[3]) return;
        SetWindowPos(fx, MemorySegment.NULL, 0, 0, c[2], c[3], Win.SHOW);
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
