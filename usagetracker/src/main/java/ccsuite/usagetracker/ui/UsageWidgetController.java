package ccsuite.usagetracker.ui;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.shape.Rectangle;
import lombok.Setter;
import ccsuite.usagetracker.model.AccountUsage;

/**
 * Controller for the FXML-based usage widget UI.
 * Manages the compact UI components for displaying account usage metrics.
 */
public class UsageWidgetController {

    // The glow of the name of the account Claude Code is on (see pulse)
    private javafx.scene.effect.DropShadow neon;
    private javafx.scene.paint.Paint plainName;

    /**
     * One beat of the active account's name: fire yellow to red with a glowing shadow. Called a few times a second
     * for every row while the window shows; a row that is not the active one is put back to its plain name.
     */
    void pulse(boolean active, boolean danger) {
        if (accName == null) return;
        if (!active) {
            if (neon != null && accName.getEffect() == neon) { accName.setEffect(null); accName.setTextFill(plainName); }
            return;
        }
        if (neon == null) { neon = new javafx.scene.effect.DropShadow(); neon.setSpread(0.4); plainName = accName.getTextFill(); }
        var fire = GlowPulse.fire(danger);
        accName.setTextFill(fire);
        neon.setColor(fire);
        neon.setRadius(GlowPulse.radius());
        accName.setEffect(neon);
    }

    // Root container (for background color change)
    private HBox rootContainer;

    // Active status checkbox
    @FXML
    private Label activeCheckbox;

    // Account information labels
    @FXML
    private Label accName;

    @FXML
    private Label accType;

    // Interactive button labels
    @FXML
    private Label pingButton;

    @FXML
    private Label refreshButton;

    // Usage percentage labels
    @FXML
    private Label percentUsage;

    @FXML
    private Label percentTime;

    // Progress bar rectangles (5-hour)
    @FXML
    private Rectangle usageProgress;

    @FXML
    private Rectangle timeProgress;

    // Weekly progress bars (7-day all models)
    @FXML
    private Label percentWeeklyUsage;

    @FXML
    private Rectangle weeklyUsageProgress;

    @FXML
    private Label weeklyUsageRatio;

    @FXML
    private Label percentWeeklyTime;

    @FXML
    private Rectangle weeklyTimeProgress;

    @FXML
    private Label weeklyResetTime;

    // Metric labels
    @FXML
    private Label timeUsageRatioAsPercent;

    @FXML
    private Label cycleEndTime;

    // Callback handlers (set by parent)
    @Setter
    private Runnable onRefresh;

    @Setter
    private Runnable onPing;

    /**
     * Set the root container (called from UsageWidget after FXML load)
     */
    public void setRootContainer(HBox root) {
        this.rootContainer = root;
    }

    /**
     * Initialize the controller after FXML loading.
     * Sets up event handlers for the interactive elements.
     */
    @FXML
    public void initialize() {
        // Root container will be set by parent after loading

        // Setup click handlers for button labels - ensure UI runs on JavaFX thread
        if (refreshButton != null) {
            refreshButton.setOnMouseClicked(event -> {
                if (onRefresh != null) {
                    // Run callback on background thread to avoid blocking UI
                    new Thread(() -> {
                        try {
                            onRefresh.run();
                        } catch (Exception e) {
                            System.err.println("Error in refresh callback: " + e.getMessage());
                        }
                    }).start();
                }
            });
            // Add hover effect
            refreshButton.setOnMouseEntered(event ->
                refreshButton.setStyle(refreshButton.getStyle() + "-fx-opacity: 0.7;"));
            refreshButton.setOnMouseExited(event ->
                refreshButton.setStyle(refreshButton.getStyle().replace("-fx-opacity: 0.7;", "")));
        }

        if (pingButton != null) {
            pingButton.setOnMouseClicked(event -> {
                if (onPing != null) {
                    // Run callback on background thread to avoid blocking UI
                    new Thread(() -> {
                        try {
                            onPing.run();
                        } catch (Exception e) {
                            System.err.println("Error in ping callback: " + e.getMessage());
                        }
                    }).start();
                }
            });
            // Add hover effect
            pingButton.setOnMouseEntered(event ->
                pingButton.setStyle(pingButton.getStyle() + "-fx-opacity: 0.7;"));
            pingButton.setOnMouseExited(event ->
                pingButton.setStyle(pingButton.getStyle().replace("-fx-opacity: 0.7;", "")));
        }
    }

    /**
     * Update the UI with account usage data.
     * Must be called on JavaFX Application Thread.
     *
     * @param accountUsage The account usage data to display
     * @param showTimeFormat The time format to show ("resetTime" or "timeRemaining")
     */
    public void updateUsageData(AccountUsage accountUsage, String showTimeFormat) {
        // Ensure we're on the JavaFX thread
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> updateUsageData(accountUsage, showTimeFormat));
            return;
        }

        // Update active status checkbox
        var isActive = accountUsage.credential().active();
        if (activeCheckbox != null) {
            activeCheckbox.setText(isActive ? "✔" : "✘");
            activeCheckbox.setStyle("-fx-text-fill: " + (isActive ? "#44FF44" : "#FF8A80") + "; -fx-font-size: 8px;");
        }

        // Update background color based on active status
        if (rootContainer != null) {
            var bgColor = isActive ? "#2A2A2A" : "#3A2A2A"; // Slightly lighter for inactive
            rootContainer.setStyle("-fx-background-color: " + bgColor + ";");
        }

        // Update account name (truncate if needed)
        var fullId = accountUsage.credential().id();
        var displayId = fullId.length() > 3 ? fullId.substring(0, 3) : fullId;
        accName.setText(displayId.toUpperCase());

        // Update account type (F for free, or tier number)
        var tier = accountUsage.credential().tier();
        accType.setText(tier == 0 ? "F" : String.valueOf(tier));

        // Update usage percentage
        var usagePercent = accountUsage.getFiveHourUsagePercent();
        percentUsage.setText(String.format("%.0f%%", usagePercent));

        // Update time percentage
        var timePercent = accountUsage.getFiveHourTimeElapsedPercent();
        percentTime.setText(String.format("%.0f%%", timePercent));

        // Update progress bar widths (max width is 40 based on FXML)
        var maxWidth = 40.0;
        usageProgress.setWidth((usagePercent / 100.0) * maxWidth);
        timeProgress.setWidth((timePercent / 100.0) * maxWidth);

        // Update progress bar colors based on usage percentage
        var usageColor = getProgressColorFromPercent(usagePercent);
        usageProgress.setFill(javafx.scene.paint.Color.web(usageColor));
        percentUsage.setStyle("-fx-text-fill: " + usageColor + "; -fx-font-size: 7px; -fx-font-weight: bold;");

        // Update utilization rate
        var utilizationRate = accountUsage.getUsageToTimeRatio() * 100.0;
        timeUsageRatioAsPercent.setText(String.format("%.0f%%", utilizationRate));

        // Update utilization color
        var rateColor = getUtilizationColor(utilizationRate);
        timeUsageRatioAsPercent.setStyle("-fx-text-fill: " + rateColor + "; -fx-font-size: 8px; -fx-font-weight: bold;");

        // Update time info based on format setting
        var timeText = "resetTime".equals(showTimeFormat)
            ? accountUsage.getCycleResetTime()
            : accountUsage.getTimeUntilReset();
        cycleEndTime.setText(timeText);

        // Update weekly progress bars (7-day all models)
        var usageData = accountUsage.usageData();
        if (usageData != null && usageData.sevenDay() != null) {
            var sevenDay = usageData.sevenDay();

            // Calculate weekly usage percentage
            var weeklyUsagePercent = sevenDay.utilization();
            if (percentWeeklyUsage != null) {
                percentWeeklyUsage.setText(String.format("%.0f%%", weeklyUsagePercent));
                // Update color based on usage
                var weeklyUsageColor = getProgressColorFromPercent(weeklyUsagePercent);
                percentWeeklyUsage.setStyle("-fx-text-fill: " + weeklyUsageColor + "; -fx-font-size: 8px; -fx-font-weight: bold;");
            }

            // Calculate weekly time elapsed percentage
            var weeklyTimePercent = calculateTimeElapsedPercent(sevenDay.resetsAt(), 7 * 24 * 60 * 60);
            if (percentWeeklyTime != null) {
                percentWeeklyTime.setText(String.format("%.0f%%", weeklyTimePercent));
            }

            // Update weekly progress bar widths
            var weeklyMaxWidth = 35.0; // Based on FXML
            if (weeklyUsageProgress != null) {
                weeklyUsageProgress.setWidth((weeklyUsagePercent / 100.0) * weeklyMaxWidth);
                // Update color based on usage
                var weeklyUsageColor = getProgressColorFromPercent(weeklyUsagePercent);
                weeklyUsageProgress.setFill(javafx.scene.paint.Color.web(weeklyUsageColor));
            }
            if (weeklyTimeProgress != null) {
                weeklyTimeProgress.setWidth((weeklyTimePercent / 100.0) * weeklyMaxWidth);
            }

            // Calculate and update weekly utilization ratio
            var weeklyUtilizationRate = weeklyTimePercent > 0 ? (weeklyUsagePercent / weeklyTimePercent) * 100.0 : 0.0;
            if (weeklyUsageRatio != null) {
                weeklyUsageRatio.setText(String.format("%.0f%%", weeklyUtilizationRate));
                var weeklyRateColor = getUtilizationColor(weeklyUtilizationRate);
                weeklyUsageRatio.setStyle("-fx-text-fill: " + weeklyRateColor + "; -fx-font-size: 8px; -fx-font-weight: bold;");
            }

            // Update weekly reset time
            if (weeklyResetTime != null) {
                var weeklyTimeText = formatWeeklyResetTime(sevenDay.resetsAt());
                weeklyResetTime.setText(weeklyTimeText);
            }
        } else {
            // No weekly data available - hide or show N/A
            if (percentWeeklyUsage != null) percentWeeklyUsage.setText("--");
            if (percentWeeklyTime != null) percentWeeklyTime.setText("--");
            if (weeklyUsageProgress != null) weeklyUsageProgress.setWidth(0);
            if (weeklyTimeProgress != null) weeklyTimeProgress.setWidth(0);
            if (weeklyUsageRatio != null) weeklyUsageRatio.setText("--");
            if (weeklyResetTime != null) weeklyResetTime.setText("--");
        }
    }

    /**
     * Calculate time elapsed percentage for any window duration
     */
    private double calculateTimeElapsedPercent(java.time.OffsetDateTime resetTimeUtc, long windowSeconds) {
        if (resetTimeUtc == null) {
            return 0.0;
        }

        var now = java.time.OffsetDateTime.now();
        var timeUntilReset = java.time.Duration.between(now, resetTimeUtc).getSeconds();
        var elapsedSeconds = windowSeconds - timeUntilReset;

        return (elapsedSeconds * 100.0) / windowSeconds;
    }

    /**
     * Format weekly reset time as "Xd Yh" (compact format)
     */
    private String formatWeeklyResetTime(java.time.OffsetDateTime resetTimeUtc) {
        if (resetTimeUtc == null) return "N/A";

        var now = java.time.OffsetDateTime.now();
        var duration = java.time.Duration.between(now, resetTimeUtc);

        if (duration.isNegative() || duration.isZero()) return "Now";

        var days = duration.toDays();
        var hours = duration.toHoursPart();
        var minutes = duration.toMinutesPart();

        if (days > 0) {
            // Skip hours if 0 (only show days)
            if (hours == 0) {
                return String.format("%dd", days);
            }
            return String.format("%dd%dh", days, hours);
        } else if (hours > 0) {
            return String.format("%dh", hours);
        } else {
            // Less than 1 hour remaining, show minutes
            return String.format("%dm", minutes);
        }
    }

    /**
     * Gets color based on usage percentage.
     *
     * @param percentage Usage percentage (0-100)
     * @return CSS color string
     */
    private String getProgressColorFromPercent(double percentage) {
        if (percentage >= 80) return "#FF8A80"; // Red
        if (percentage >= 60) return "#FFAA00"; // Orange
        return "#44FF44"; // Green
    }

    /**
     * Gets color based on utilization rate percentage.
     * Utilization rate = (usage / time elapsed) * 100
     *
     * @param rate Utilization rate percentage
     * @return CSS color string
     */
    private String getUtilizationColor(double rate) {
        if (rate >= 120) return "#FF8A80"; // Red - using too fast
        if (rate >= 100) return "#FFAA00"; // Orange - right at the edge
        return "#44FF44"; // Green - using slower than time passing
    }

    /**
     * Set the refresh button visibility.
     */
    public void setRefreshButtonVisible(boolean visible) {
        if (refreshButton != null) {
            refreshButton.setVisible(visible);
        }
    }

    /**
     * Set the ping button visibility.
     */
    public void setPingButtonVisible(boolean visible) {
        if (pingButton != null) {
            pingButton.setVisible(visible);
        }
    }

    /**
     * Set the refresh button busy state (red color, disabled).
     * Call on JavaFX thread.
     */
    public void setRefreshButtonBusy(boolean busy) {
        if (refreshButton == null) return;

        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> setRefreshButtonBusy(busy));
            return;
        }

        if (busy) {
            // Make red and disable mouse events
            refreshButton.setStyle("-fx-text-fill: #FF8A80; -fx-cursor: default; -fx-opacity: 0.7;");
            refreshButton.setDisable(true);
        } else {
            // Restore normal state
            refreshButton.setStyle("-fx-text-fill: white; -fx-cursor: hand;");
            refreshButton.setDisable(false);
        }
    }

    /**
     * Set the ping button busy state (red color, disabled).
     * Call on JavaFX thread.
     */
    public void setPingButtonBusy(boolean busy) {
        if (pingButton == null) return;

        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> setPingButtonBusy(busy));
            return;
        }

        if (busy) {
            // Make red and disable mouse events
            pingButton.setStyle("-fx-text-fill: #FF8A80; -fx-cursor: default; -fx-opacity: 0.7;");
            pingButton.setDisable(true);
        } else {
            // Restore normal state
            pingButton.setStyle("-fx-text-fill: white; -fx-cursor: hand;");
            pingButton.setDisable(false);
        }
    }
}
