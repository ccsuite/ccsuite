package ccsuite.ccacc.trim;

import java.util.List;
import java.util.Set;

/** What the trimmer needs from the machine; the real one is {@link WinProbe}, tests use a fake. */
interface Probe {

    /** The claude.exe processes of the current user, and no others. */
    List<Proc> claudes();

    /** Trims that process (after checking again that it still is that claude.exe) and returns its memory right after; null when it could not be done. */
    Mem trim(Proc p);

    /** The process and its ancestors, so a terminal tab can be matched to the claude.exe under it. */
    Set<Long> lineage(Proc p);

    /** Free physical RAM in bytes. */
    long freeRam();
}
