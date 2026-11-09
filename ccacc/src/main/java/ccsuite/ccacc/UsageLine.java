package ccsuite.ccacc;

import ccsuite.base.model.Usage;
import ccsuite.base.usage.Cooldown;
import ccsuite.usagetracker.ui.UsageDetails;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;

/** One window of an account as a line: the usage bar, the bar of the time gone, the ratio of the two and the reset (and a tail such as the cool-down). Created once, updated in place. */
final class UsageLine extends HBox {

    static final String MONO = "-fx-font-family: 'Consolas', 'Monospaced'; -fx-font-size: 12;";
    static final double USE = 140, TIME = 90;

    final long window;
    final String noReset, timeColor;
    final Bar use = new Bar(USE), time = new Bar(TIME);
    final Label usePct = text("", ""), timePct = text("", ""), ratio = text("", ""), reset = text("", "-fx-text-fill: #555;"), tail = text("", "-fx-text-fill: #8a3b1c; -fx-font-weight: bold;");

    UsageLine(String label, long window, String noReset, String timeColor) {
        super(6);
        this.window = window; this.noReset = noReset; this.timeColor = timeColor;
        var name = text(label, "-fx-font-weight: bold;");
        name.setMinWidth(36);
        usePct.setMinWidth(36); timePct.setMinWidth(36); ratio.setMinWidth(78);
        setAlignment(Pos.CENTER_LEFT);
        getChildren().addAll(name, use, usePct, time, timePct, ratio, reset, tail);
    }

    void update(Usage u, String tailText) {
        var usage = u == null ? Double.NaN : u.utilization();
        var gone = u == null || u.resetsAt() == null ? Double.NaN : UsageDetails.elapsed(u.resetsAt(), window);
        var r = Cooldown.ratio(usage, gone);
        use.set(usage, color(usage));
        time.set(gone, timeColor);
        usePct.setText(pct(usage));
        timePct.setText(pct(gone));
        ratio.setText(Double.isNaN(r) ? "ratio   -" : "ratio %3d%%".formatted(Math.round(r)));
        ratio.setStyle(MONO + " -fx-font-weight: bold; -fx-text-fill: " + (Double.isNaN(r) ? "#999" : rate(r)) + ";");
        reset.setText(u == null ? "no reading" : u.resetsAt() == null ? noReset : "resets " + UsageDetails.reset(u.resetsAt()));
        tail.setText(tailText);
    }

    static Label text(String s, String style) {
        var l = new Label(s);
        l.setStyle(MONO + " " + style);
        return l;
    }

    static String pct(double v) { return Double.isNaN(v) ? "  -" : "%3d%%".formatted(Math.round(v)); }

    static String color(double p) { return Double.isNaN(p) ? "#9a9a9a" : p >= 80 ? "#b3261e" : p >= 60 ? "#e0a100" : "#2e9e44"; }

    static String rate(double r) { return r >= 120 ? "#b3261e" : r >= 100 ? "#e0a100" : "#2e9e44"; }
}
