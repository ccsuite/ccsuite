package ccsuite.ccacc.migrate;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import lombok.Data;
import lombok.experimental.Accessors;

/** One archived file in the manifest: what it was, where it came from, where it is now (relative to the archive), and its SHA-256 as it was. */
@Data
@Accessors(fluent = true, chain = true)
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY, getterVisibility = JsonAutoDetect.Visibility.NONE, isGetterVisibility = JsonAutoDetect.Visibility.NONE, setterVisibility = JsonAutoDetect.Visibility.NONE)
public class ArchiveEntry {
    String kind, note, original, archived, sha256;
}
