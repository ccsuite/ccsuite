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
    /** The gate (keys, grants, budgets, ledger) on 127.0.0.1:7787; off until switched on. */
    boolean gate = false;
    int gatePort = 7787;
    /** The outward link to the relay (phase 2): remote callers reach the gate through it; off until switched on. */
    boolean relay = false;
    int relayWorkers = 4;
    /** The three ports with modes (pclaude, fclaude, pfclaude) over the paid and the free lane, and the routing settings; off until switched on. */
    LaneSettings lanes = new LaneSettings();
    WatchMode watchMode = WatchMode.AUTO;
}
