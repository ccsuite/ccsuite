package ccsuite.usagetracker.model;

import lombok.Data;
import lombok.experimental.Accessors;
import ccsuite.base.model.CCAPICredential;
import ccsuite.base.model.Usage;
import ccsuite.base.model.UsageData;

import java.time.Duration;
import java.time.OffsetDateTime;

/**
 * Account usage with calculated metrics for display.
 * Now uses shared CCAPICredential from base module.
 */
@Data
@Accessors(fluent = true, chain = true)
public class AccountUsage {

    private CCAPICredential credential;
    private UsageData usageData;
    private OffsetDateTime fetchedAt;

    public AccountUsage(CCAPICredential credential, UsageData usageData) {
        this.credential = credential;
        this.usageData = usageData;
        this.fetchedAt = OffsetDateTime.now();
    }

    /**
     * Get 5-hour usage percentage
     */
    public double getFiveHourUsagePercent() {
        if (usageData == null || usageData.fiveHour() == null) {
            return 0.0;
        }
        return usageData.fiveHour().utilization();
    }

    /**
     * Get time elapsed percentage for 5-hour window
     */
    public double getFiveHourTimeElapsedPercent() {
        if (usageData == null) {
            return 0.0;
        }
        var fiveHour = usageData.fiveHour();
        if (fiveHour == null || fiveHour.resetsAt() == null) {
            return 0.0;
        }

        var now = OffsetDateTime.now();
        var resetTime = fiveHour.resetsAt();
        var fiveHoursInSeconds = 5 * 60 * 60;

        // Calculate elapsed time
        var timeUntilReset = Duration.between(now, resetTime).getSeconds();
        var elapsedSeconds = fiveHoursInSeconds - timeUntilReset;

        return (elapsedSeconds * 100.0) / fiveHoursInSeconds;
    }

    /**
     * Get ratio comparing usage to time elapsed
     * > 1.0 means using more than proportional to time
     * < 1.0 means using less than proportional to time
     * = 1.0 means usage matches time proportionally
     */
    public double getUsageToTimeRatio() {
        var timePercent = getFiveHourTimeElapsedPercent();
        if (timePercent == 0.0) {
            return 0.0;
        }
        return getFiveHourUsagePercent() / timePercent;
    }

    private static final long WEEKLY_WINDOW_SECONDS = 7L * 24 * 60 * 60;

    /**
     * Estimated cool-down duration: how long to leave the account idle for the weekly
     * utilization RATE (usage% ÷ time-elapsed%) to fall back to 100%.
     *
     * While idle, usage% stays flat and time-elapsed% keeps growing, so the rate drops.
     * The rate hits 100% when time-elapsed% catches up to usage%, i.e. when elapsed time
     * reaches (usage%/100 × window). Cool-down = that target elapsed minus the elapsed so far.
     *
     * Returns null when the rate is already ≤100% (no cool-down needed) or data is unavailable.
     */
    public Duration getWeeklyCoolDownDuration() {
        if (usageData == null || usageData.sevenDay() == null) {
            return null;
        }
        var sevenDay = usageData.sevenDay();
        var resetsAt = sevenDay.resetsAt();
        if (resetsAt == null) {
            return null;
        }
        var usagePercent = sevenDay.utilization(); // flat while idle

        var timeUntilResetSec = Duration.between(OffsetDateTime.now(), resetsAt).getSeconds();
        var elapsedSec = WEEKLY_WINDOW_SECONDS - timeUntilResetSec;

        // Idle until time-elapsed% catches up to usage% → rate == 100%
        var targetElapsedSec = (long) (usagePercent / 100.0 * WEEKLY_WINDOW_SECONDS);
        var coolDownSec = targetElapsedSec - elapsedSec;

        if (coolDownSec <= 0) {
            return null; // rate already ≤100%, no cool-down needed
        }
        // Never wait past the reset; the window rolls over there anyway
        if (timeUntilResetSec > 0 && coolDownSec > timeUntilResetSec) {
            coolDownSec = timeUntilResetSec;
        }
        return Duration.ofSeconds(coolDownSec);
    }

    /**
     * Human-readable cool-down string (e.g. "18h", "1d 6h"), or null if not needed.
     */
    public String getWeeklyCoolDownFormatted() {
        var duration = getWeeklyCoolDownDuration();
        if (duration == null) return null;

        var days = duration.toDays();
        var hours = duration.toHoursPart();
        var minutes = duration.toMinutesPart();

        if (days > 0) {
            return hours == 0 ? String.format("%dd", days) : String.format("%dd %dh", days, hours);
        } else if (hours > 0) {
            return minutes == 0 ? String.format("%dh", hours) : String.format("%dh %dm", hours, minutes);
        } else {
            return String.format("%dm", minutes);
        }
    }

    /**
     * Get formatted status string for CLI display
     */
    public String getFormattedStatus() {
        var id = credential.id();
        var fiveHourUsage = getFiveHourUsagePercent();
        var timeElapsed = getFiveHourTimeElapsedPercent();
        var ratio = getUsageToTimeRatio();

        return String.format("%s | 5hr: %5.1f%% | Time: %5.1f%% | Ratio: %.2f",
                id, fiveHourUsage, timeElapsed, ratio);
    }

    /**
     * Get time remaining until reset
     */
    public String getTimeUntilReset() {
        if (usageData == null) {
            return "N/A";
        }
        var fiveHour = usageData.fiveHour();
        if (fiveHour == null || fiveHour.resetsAt() == null) {
            return "N/A";
        }

        var duration = Duration.between(OffsetDateTime.now(), fiveHour.resetsAt());
        var hours = duration.toHours();
        var minutes = duration.toMinutesPart();

        // Skip hours if 0 (only show minutes)
        if (hours == 0) {
            return String.format("%dm", minutes);
        }

        // Skip minutes if 0 (only show hours)
        if (minutes == 0) {
            return String.format("%dh", hours);
        }

        return String.format("%dh%dm", hours, minutes);
    }

    /**
     * Get cycle reset time in HH:mm format (system local time, rounded to nearest 30-min boundary)
     */
    public String getCycleResetTime() {
        if (usageData == null) {
            return "N/A";
        }
        var fiveHour = usageData.fiveHour();
        if (fiveHour == null || fiveHour.resetsAt() == null) {
            return "N/A";
        }

        // Convert UTC to system local time zone
        var resetTimeUtc = fiveHour.resetsAt();
        var resetTimeLocal = resetTimeUtc.atZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalDateTime();

        // Round to nearest 30-minute boundary: add 15 minutes, then round down to 30-min intervals
        var totalMinutes = resetTimeLocal.getHour() * 60 + resetTimeLocal.getMinute();
        var roundedTotalMinutes = ((totalMinutes + 15) / 30) * 30;
        var roundedHour = (roundedTotalMinutes / 60) % 24;
        var roundedMinute = roundedTotalMinutes % 60;

        return String.format("%02d:%02d", roundedHour, roundedMinute);
    }
}
