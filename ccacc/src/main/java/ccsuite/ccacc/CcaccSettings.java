package ccsuite.ccacc;

import ccsuite.oauthswap.watch.WatchMode;
import com.fasterxml.jackson.annotation.JsonAutoDetect;
import lombok.Data;
import lombok.experimental.Accessors;

/** Which parts run, and the watcher's mode; kept in {@code ~/ccsuite/ccacc/settings.json} and changed from the tray menu. */
@Data
@Accessors(fluent = true, chain = true)
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY, getterVisibility = JsonAutoDetect.Visibility.NONE, isGetterVisibility = JsonAutoDetect.Visibility.NONE)
public class CcaccSettings {

    /** The account watcher (what {@code cswap watch} did). */
    boolean watch = true;
    /** The usage widget and taskbar card (what ccu did). */
    boolean widget = true;
    /** The local proxy (what ccls did); off until switched on. */
    boolean server = false;
    WatchMode watchMode = WatchMode.AUTO;
}
