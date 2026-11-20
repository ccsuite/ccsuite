package ccsuite.taskbarslot;

import module java.base;
import static ccsuite.taskbarslot.win32.User32.*;

/**
 * Hosts one tool's window in the slot band: a JavaFX (or any top-level) window with a known title is re-parented into the band window the slot DLL registered in Explorer's
 * ReBar, and kept in its slot. Call {@link #tick} about once a second on the thread that owns the window.
 */
public class Slot {
    public final String title;
    final Supplier<int[]> where;
    final boolean waits;
    public MemorySegment band = MemorySegment.NULL, fx = MemorySegment.NULL;
    long fxBefore, bandBefore;
    public boolean embedded;

    /** {@code where} gives {first slot, slots spanned} on every call, or a negative first slot while the card has no place. A card that {@code waits} embeds only once the band is long enough to hold its slots. */
    protected Slot(String title, Supplier<int[]> where, boolean waits) { this.title = title; this.where = where; this.waits = waits; }

    /** The card registered under {@code id}: one slot, after the legacy ones, in registered order. It waits until the band is long enough to hold it. */
    public static Slot registered(Cards cards, String id, String title) {
        return new Slot(title, () -> new int[]{cards.position(id) < 0 ? -1 : legacy() + cards.position(id), 1}, true);
    }

    /** True on a vertical taskbar (taller than wide). */
    public static boolean vertical() {
        var r = Win.rect(Win.findWindow("Shell_TrayWnd", null), false);
        return r[3] - r[1] > r[2] - r[0];
    }

    static MemorySegment rebar() { return Win.findChild(Win.findWindow("Shell_TrayWnd", null), "ReBarWindow32"); }

    static String className(int cards) { return cards <= 1 ? "CcuSlotWnd" : "CcuSlotWnd" + cards; }

    /** How many cards long the band was when its DLL made it, from its window class: CcuSlotWnd (one), CcuSlotWnd2, CcuSlotWnd3 ... An older DLL only ever makes one or two. */
    static int classTotal() {
        var r = rebar();
        for (var n = Cards.MAX; n >= 2; n--) if (!Win.none(Win.findChild(r, className(n)))) return n;
        return 1;
    }

    public static MemorySegment findBand() {
        var r = rebar();
        var b = Win.findChild(r, className(classTotal()));
        return Win.none(b) ? Win.findChild(r, "CcuDeskBandWnd") : b;
    }

    /**
     * {legacy, total} of the band: how many cards ccacc owns (1 or 2) and how long the band is now. The DLL gives its window the text "legacy:total", and changes it when it
     * is told to lay out again, while its class keeps saying how long the band was made. An older DLL gave no text, and then the whole band was ccacc's.
     */
    public static int[] sizes() {
        var b = findBand();
        if (Win.none(b)) return new int[]{1, 1};
        var r = rebar();
        var t0 = classTotal();
        for (var l = 1; l <= 2; l++) if (Win.same(Win.findChild(r, null, l + ":" + t0), b)) return new int[]{l, t0};
        for (var t = 1; t <= Cards.MAX; t++) for (var l = 1; l <= 2; l++) if (Win.same(Win.findChild(r, null, l + ":" + t), b)) return new int[]{l, t};
        return new int[]{t0, t0};
    }

    public static int total() { return sizes()[1]; }

    public static int legacy() { return sizes()[0]; }

    boolean fits(int[] w) { return w[0] >= 0 && (!waits || total() >= w[0] + w[1]); }

    public boolean embed() {
        var f = Win.findWindow(null, title);
        var b = findBand();
        if (Win.none(f) || Win.none(b) || !fits(where.get())) return false;
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

    public void place(boolean force) {
        var c = Win.rect(band, true);
        var f = Win.rect(fx, true);
        var w = where.get();
        if (w[0] < 0) return;
        var t = BandLayout.target(c[2], c[3], vertical(), total(), w[0], w[1]);
        if (!force && f[2] == t[2] && f[3] == t[3] && Win.rect(fx, false)[0] == Win.rect(band, false)[0] + t[0] && Win.rect(fx, false)[1] == Win.rect(band, false)[1] + t[1]) return;
        SetWindowPos(fx, MemorySegment.NULL, t[0], t[1], t[2], t[3], Win.SHOW);
        RedrawWindow(fx, MemorySegment.NULL, MemorySegment.NULL, RDW_INVALIDATE() | RDW_ERASE() | RDW_ALLCHILDREN() | RDW_UPDATENOW());
    }

    public boolean alive() { return Win.same(GetParent(fx), band); }

    /** One beat: embeds when it can, keeps the card in its slot, and says LOST when the band is gone (the window then has to be made again). */
    public SlotState tick() {
        if (!embedded) return embed() ? SlotState.EMBEDDED : SlotState.WAITING;
        if (!alive()) { embedded = false; return SlotState.LOST; }
        place(false);
        return SlotState.EMBEDDED;
    }

    public void release() {
        if (!embedded) return;
        embedded = false;
        SetParent(fx, MemorySegment.NULL);
        SetWindowLongPtrW(fx, GWL_STYLE(), fxBefore);
        SetWindowPos(fx, MemorySegment.NULL, -3000, 0, 0, 0, SWP_NOSIZE() | SWP_NOZORDER() | SWP_NOACTIVATE());
        SetWindowLongPtrW(band, GWL_STYLE(), bandBefore);
    }
}
