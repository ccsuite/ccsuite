package ccsuite.usagetracker.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.experimental.Accessors;

import jakarta.xml.bind.annotation.*;

/**
 * Settings model for usage tracker configuration.
 * Now only contains UI settings. Credentials are loaded from shared CCAPIsCredentials.xml.
 * Mapped to CCAPIsUsageTrackerSettings.xml in user home directory.
 */
@Data
@JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY, getterVisibility = JsonAutoDetect.Visibility.NONE, isGetterVisibility = JsonAutoDetect.Visibility.NONE, setterVisibility = JsonAutoDetect.Visibility.NONE)
@JsonInclude(JsonInclude.Include.NON_NULL)
@Accessors(fluent = true, chain = true)
@XmlRootElement(name = "CCAPIsUsageTrackerSettings")
@XmlAccessorType(XmlAccessType.FIELD)
public class Settings {

    @XmlElement(name = "ui")
    private UISettings ui;

    /**
     * UI settings for widget appearance and behavior
     */
    @Data
    @JsonAutoDetect(fieldVisibility = JsonAutoDetect.Visibility.ANY, getterVisibility = JsonAutoDetect.Visibility.NONE, isGetterVisibility = JsonAutoDetect.Visibility.NONE, setterVisibility = JsonAutoDetect.Visibility.NONE)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Accessors(fluent = true, chain = true)
    @XmlAccessorType(XmlAccessType.FIELD)
    public static class UISettings {

        @XmlAttribute(name = "layout")
        private String layout = "horizontal"; // horizontal or vertical (future)

        @XmlAttribute(name = "sideBar", required = false)
        private Boolean sideBar; // true = compact vertical move/button bar on the left edge (default: top bar)

        @XmlAttribute(name = "showTime")
        private String showTime = "timeRemaining"; // timeRemaining or resetTime

        @XmlAttribute(name = "idMaxLen")
        private int idMaxLen = 10; // Maximum length of account ID to display

        @XmlAttribute(name = "lastScreenLoc")
        private String lastScreenLoc; // Format: "x,y,height" - last screen position and height for window (auto-saved)

        @XmlAttribute(name = "usageRefreshIntervalSecs", required = false)
        private Integer usageRefreshIntervalSecs; // Refresh interval in seconds (default: 60)

        @XmlAttribute(name = "usageFetchSpacingMs", required = false)
        private Integer usageFetchSpacingMs; // Delay between consecutive account fetches (default: 1200) - prevents server rate-limit/blacklist

        @XmlAttribute(name = "usageMaxRetries", required = false)
        private Integer usageMaxRetries; // Max attempts per account fetch (default: 4)

        @XmlAttribute(name = "usageRetryBaseMs", required = false)
        private Integer usageRetryBaseMs; // Base delay for exponential backoff (default: 800)

        @XmlAttribute(name = "usageRetryMaxMs", required = false)
        private Integer usageRetryMaxMs; // Cap for exponential backoff delay (default: 20000)

        @XmlAttribute(name = "pingIntervalDurSec", required = false)
        private Integer pingIntervalDurSec; // Ping interval duration in seconds

        @XmlAttribute(name = "pingMessageVisible", required = false)
        private Boolean pingMessageVisible; // Whether ping messages should be visible (default: true)

        @XmlAttribute(name = "taskbar", required = false)
        private Boolean taskbar; // true = show the usage card inside the Windows taskbar (installs a small per-user DLL); default false

        @XmlAttribute(name = "floatingWindow", required = false)
        private Boolean floatingWindow; // false = do not show the floating window at start (still available from the tray); default true

        @XmlAttribute(name = "glow", required = false)
        private Boolean glow; // false = the name of the account Claude Code is on does not beat in fire colours; default true

        // Glow of the active account's name; -Dccu.glow=true|false overrides the file
        public boolean glow() {
            var p = System.getProperty("ccu.glow");
            return p != null ? Boolean.parseBoolean(p) : glow == null || glow;
        }

        // Taskbar card on/off; -Dccu.taskbar=true|false overrides the file (for testing)
        public boolean taskbar() {
            var p = System.getProperty("ccu.taskbar");
            return p != null ? Boolean.parseBoolean(p) : taskbar != null && taskbar;
        }

        // Floating window at start; -Dccu.floating=true|false overrides the file (for testing)
        public boolean floatingWindow() {
            var p = System.getProperty("ccu.floating");
            return p != null ? Boolean.parseBoolean(p) : floatingWindow == null || floatingWindow;
        }

        // Return usageRefreshIntervalSecs with default value of 60 if not set or invalid
        public int usageRefreshIntervalSecs() {
            if (usageRefreshIntervalSecs != null && usageRefreshIntervalSecs > 0) {
                return usageRefreshIntervalSecs;
            }
            return 60; // Default: 60 seconds
        }

        // Spacing between account fetches in ms (default: 1200). 0 disables spacing.
        public int usageFetchSpacingMs() {
            return (usageFetchSpacingMs != null && usageFetchSpacingMs >= 0) ? usageFetchSpacingMs : 1200;
        }

        // Max attempts per account fetch (default: 4)
        public int usageMaxRetries() {
            return (usageMaxRetries != null && usageMaxRetries > 0) ? usageMaxRetries : 4;
        }

        // Base backoff delay in ms (default: 800)
        public int usageRetryBaseMs() {
            return (usageRetryBaseMs != null && usageRetryBaseMs > 0) ? usageRetryBaseMs : 800;
        }

        // Backoff delay cap in ms (default: 20000)
        public int usageRetryMaxMs() {
            return (usageRetryMaxMs != null && usageRetryMaxMs > 0) ? usageRetryMaxMs : 20000;
        }

        // Return pingIntervalDurSec with default value of 3600 (1 hour) if not set or invalid
        public int pingIntervalDurSec() {
            if (pingIntervalDurSec != null && pingIntervalDurSec > 0) {
                return pingIntervalDurSec;
            }
            return 3600; // Default: 1 hour
        }

        // Return pingMessageVisible with default value of true if not set
        public boolean pingMessageVisible() {
            return pingMessageVisible != null ? pingMessageVisible : true;
        }

        // Side-bar (left) mode; default false = top bar
        public boolean sideBar() {
            return sideBar != null ? sideBar : false;
        }
    }

    /**
     * Get UI settings with defaults if not configured
     */
    public UISettings getUiSettings() {
        if (ui == null) {
            ui = new UISettings();
        }
        return ui;
    }
}
