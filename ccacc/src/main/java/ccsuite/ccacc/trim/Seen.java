package ccsuite.ccacc.trim;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import lombok.Data;
import lombok.experimental.Accessors;

/** What the trimmer remembers about one claude.exe between two looks: when it last was busy and when it was last trimmed. */
@Data
@Accessors(fluent = true, chain = true)
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY, getterVisibility = JsonAutoDetect.Visibility.NONE, isGetterVisibility = JsonAutoDetect.Visibility.NONE)
public class Seen {
    long pid, startMs, cpuMs, atMs, busyAtMs, lastTrimMs, ioOps = -1;
    int descendants;
}
