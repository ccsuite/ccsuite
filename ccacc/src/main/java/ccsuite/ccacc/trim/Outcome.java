package ccsuite.ccacc.trim;

import lombok.Data;
import lombok.experimental.Accessors;

/** One trim that was done: the process, its memory before and after (bytes), and the MB given back so far today. */
@Data
@Accessors(fluent = true, chain = true)
public class Outcome {
    long pid, wsBefore, wsAfter, privBefore, privAfter;
    String name = "";
    double todayMb;
}
