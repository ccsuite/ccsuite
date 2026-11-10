package ccsuite.ccacc.trim;

import lombok.Data;
import lombok.experimental.Accessors;

/** One claude.exe as read at one moment: its memory, and the CPU of it and its children together. */
@Data
@Accessors(fluent = true, chain = true)
public class Proc {
    long pid, startMs, cpuMs, workingSet, privateBytes;
    /** Read plus write operations so far; -1 when unreadable. */
    long ioOps = -1;
    int descendants;
    /** The session name, from Tabs when it answered; empty otherwise. */
    String name = "";
}
