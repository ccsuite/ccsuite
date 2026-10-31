package ccsuite.base.listener;

import lombok.Data;
import lombok.experimental.Accessors;
import ccsuite.base.model.CCAPICredential;
import ccsuite.base.model.UsageData;

/**
 * Event containing usage update information.
 * This is passed from usage_tracker to listeners (like ping service).
 */
@Data
@Accessors(fluent = true, chain = true)
public class UsageUpdateEvent {

    /** The credential for which usage was updated */
    private CCAPICredential credential;

    /** Usage data (can be null if fetch failed) */
    private UsageData usageData;

    /** Timestamp of this event */
    private long timestamp = System.currentTimeMillis();

    /**
     * Check if usage is zero or null (indicating need for ping)
     */
    public boolean isUsageZeroOrNull() {
        if (usageData == null) {
            return true;
        }

        var fiveHour = usageData.fiveHour();
        if (fiveHour == null) {
            return true;
        }

        var utilization = fiveHour.utilization();
        return utilization == 0;
    }

}
