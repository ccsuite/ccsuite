package ccsuite.usagetracker.taskbar;

import module java.base;

/** The Win32 helpers of the slot host, kept under this name; they live in {@code ccsuite.taskbarslot.Win}. */
final class Win {
    private Win() {}

    static MemorySegment findWindow(String cls, String title) { return ccsuite.taskbarslot.Win.findWindow(cls, title); }
    static MemorySegment findChild(MemorySegment parent, String cls) { return ccsuite.taskbarslot.Win.findChild(parent, cls); }
    static int[] rect(MemorySegment h, boolean client) { return ccsuite.taskbarslot.Win.rect(h, client); }
    static boolean same(MemorySegment a, MemorySegment b) { return ccsuite.taskbarslot.Win.same(a, b); }
    static boolean none(MemorySegment h) { return ccsuite.taskbarslot.Win.none(h); }
}
