package ccsuite.ccacc.trim;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import lombok.Data;
import lombok.experimental.Accessors;

/** PRP 89: the idle trimmer's settings, kept in {@code ~/ccsuite/ccacc/trim-settings.json}. Shipped off: trimming real sessions starts only when the user switches it on. */
@Data
@Accessors(fluent = true, chain = true)
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY, getterVisibility = JsonAutoDetect.Visibility.NONE, isGetterVisibility = JsonAutoDetect.Visibility.NONE)
public class TrimSettings {

    boolean enabled = false;
    /** A claude.exe is idle after this many minutes without activity (CPU of it and its children, a call in flight on a lane, any read or write the process makes). */
    int idleMinutes = 5;
    /** Not worth trimming under this working set. */
    int minWorkingSetMb = 80;
    /** The same process is not trimmed again within this many minutes. */
    int minGapMinutes = 10;
    /** Trim only while free RAM is under this many GB; 0 means always. */
    double onlyBelowFreeGb = 0;
    int checkSeconds = 30;
    /** CPU use of a process and its children, in percent of one core, from which it counts as busy. */
    double busyCpuPercent = 5.0;
}
