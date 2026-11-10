package ccsuite.ccacc.trim;

import lombok.Data;
import lombok.experimental.Accessors;

/** Working set and private bytes of a process, in bytes. */
@Data
@Accessors(fluent = true, chain = true)
public class Mem {
    long workingSet, privateBytes;
}
