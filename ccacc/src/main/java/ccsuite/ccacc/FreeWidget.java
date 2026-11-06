package ccsuite.ccacc;

import module java.base;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

/**
 * The usage of the free accounts as a small window that stays on top, the counterpart of the paid accounts' widget: one line per account with its five-hour
 * and its weekly bar and the time to the next reset, and a header with how many have room. Opened and closed from the second tray icon ({@link FreeTray});
 * it remembers where it was put and whether it was showing, in {@code ~/ccsuite/ccacc/free-widget.json}. Esc hides it.
 */
final class FreeWidget {

    static final double BAR = 84;
    static final String MONO = "-fx-font-family: 'Consolas', 'Monospaced'; -fx-font-size: 12;";

    final FreeGauges gauges;
    final LaneSettings limits;
    final Path file;
    final Stage stage = new Stage(StageStyle.UTILITY);
    final VBox rows = new VBox(3);
    final Label head = new Label();
    final Timeline tick = new Timeline(new KeyFrame(Duration.seconds(30), e -> redraw()));
    boolean quiet;

    FreeWidget(FreeGauges gauges, LaneSettings limits, String color, Path file) {
        this.gauges = gauges; this.limits = limits; this.file = file;
        head.setStyle("-fx-font-weight: bold; -fx-font-size: 13; -fx-text-fill: white;");
        var band = new VBox(head);
        band.setPadding(new Insets(6, 10, 6, 10));
        band.setStyle("-fx-background-color: " + color + ";");
        rows.setPadding(new Insets(8, 10, 8, 10));
        var scene = new Scene(new VBox(band, rows));
        scene.setOnKeyPressed(e -> { if (e.getCode() == KeyCode.ESCAPE) hide(); });
        stage.setTitle("Free accounts");
        stage.getIcons().setAll(FxImages.windowIcons());
        stage.setAlwaysOnTop(true);
        stage.setScene(scene);
        stage.setOnHidden(e -> save());
        tick.setCycleCount(Timeline.INDEFINITE);
        restore();
    }

    boolean showing() { return stage.isShowing(); }

    void toggle() { if (stage.isShowing()) hide(); else show(); }

    void show() {
        redraw();
        tick.play();
        stage.show();
        stage.toFront();
        save();
    }

    void hide() {
        tick.stop();
        stage.hide();
    }

    /** Hidden because there is nothing to show (no free account), not because the user closed it: what was remembered stays. */
    void hideQuietly() {
        quiet = true;
        hide();
        quiet = false;
    }

    void redrawLater() { Platform.runLater(this::redraw); }

    void redraw() {
        if (!stage.isShowing()) return;
        var snapshot = gauges.snapshot();
        var now = Instant.now();
        var overall = FreeOverall.of(snapshot, limits.freeFiveLimit(), limits.freeWeekLimit(), now);
        head.setText("Free accounts - overall");
        var nodes = new ArrayList<javafx.scene.Node>();
        nodes.add(overallBlock(overall, snapshot.isEmpty()));
        if (!snapshot.isEmpty()) {
            var each = new Label("Each account");
            each.setStyle("-fx-text-fill: #666; -fx-font-size: 11; -fx-padding: 6 0 0 0;");
            nodes.add(each);
            snapshot.forEach(r -> nodes.add(row(r, now)));
        }
        rows.getChildren().setAll(nodes);
        stage.sizeToScene();
    }

    /** The first thing the window shows: all the free accounts as one, drawn like the paid widget's overall page. */
    static VBox overallBlock(FreeOverall o, boolean empty) {
        var box = new VBox(4);
        if (empty) { box.getChildren().add(new Label("Not read yet: the first probe of each free account takes a few seconds.")); return box; }
        var five = new Label("5h");
        five.setMinWidth(40);
        five.setStyle(MONO + " -fx-font-weight: bold;");
        var fiveLine = new HBox(6, five, bar(o.fiveFraction(), 200), pct(o.fiveFraction()), note(Double.isNaN(o.fiveTime()) || o.fiveTime() == 0 ? "" : "time gone " + Math.round(o.fiveTime()) + "%  rate " + Math.round(o.fiveRate()) + "%"));
        var week = new Label("week");
        week.setMinWidth(40);
        week.setStyle(MONO + " -fx-font-weight: bold;");
        var weekLine = new HBox(6, week, bar(o.weekFraction(), 200), pct(o.weekFraction()), note("average of " + o.weekCount() + " accounts"));
        fiveLine.setAlignment(Pos.CENTER_LEFT);
        weekLine.setAlignment(Pos.CENTER_LEFT);
        box.getChildren().addAll(fiveLine, weekLine, note(o.withRoom() + " of " + o.accounts() + " with room, about " + String.format("%.1f", o.windowsLeft()) + " five-hour windows left across them"));
        return box;
    }

    static Label note(String text) {
        var l = new Label(text);
        l.setStyle(MONO + " -fx-text-fill: #666;");
        return l;
    }

    HBox row(FreeGauges.Row r, Instant now) {
        var id = new Label(r.id());
        id.setMinWidth(40);
        id.setStyle(MONO + " -fx-font-weight: bold;" + (r.problem() != null && r.five() == null ? " -fx-text-fill: #a33;" : ""));
        var reset = new Label(r.problem() != null && r.five() == null ? "! " + r.problem() : FreeGauges.untilReset(r.resetsAt(), now));
        reset.setStyle(MONO + " -fx-text-fill: #666;");
        var line = new HBox(6, id, bar(r.five(), BAR), pct(r.five()), bar(r.seven(), 56), pct(r.seven()), reset);
        line.setAlignment(Pos.CENTER_LEFT);
        return line;
    }

    static Label pct(Double f) {
        var l = new Label(f == null ? "  -" : String.format("%3d%%", Math.round(f * 100)));
        l.setMinWidth(34);
        l.setStyle(MONO);
        return l;
    }

    static StackPane bar(Double fraction, double width) {
        var track = new Region();
        track.setMinSize(width, 9); track.setPrefSize(width, 9); track.setMaxSize(width, 9);
        track.setStyle("-fx-background-color: #dcdcdc; -fx-background-radius: 3;");
        var fill = new Region();
        var w = fraction == null ? 0 : Math.max(2, width * Math.min(1, fraction));
        fill.setMinSize(w, 9); fill.setPrefSize(w, 9); fill.setMaxSize(w, 9);
        fill.setStyle("-fx-background-color: " + FreeSummary.colour(fraction) + "; -fx-background-radius: 3;");
        var s = new StackPane(track, fill);
        s.setAlignment(Pos.CENTER_LEFT);
        s.setMaxWidth(width);
        return s;
    }

    // ---- position and visibility, remembered between runs

    void restore() {
        try {
            var j = CcaccSettingsStore.JSON.readTree(file.toFile());
            if (j.has("x") && j.has("y")) { stage.setX(j.get("x").asDouble()); stage.setY(j.get("y").asDouble()); }
        } catch (IOException | RuntimeException e) {
            // first run, or an unreadable file: the window opens where the system puts it
        }
    }

    /** Whether the widget was showing when ccacc last ran; true on the first run, so the user sees where the free usage is. */
    boolean wasShowing() {
        try { return CcaccSettingsStore.JSON.readTree(file.toFile()).path("visible").asBoolean(true); }
        catch (IOException | RuntimeException e) { return true; }
    }

    void save() {
        if (quiet) return;
        try {
            Files.createDirectories(file.getParent());
            var j = CcaccSettingsStore.JSON.createObjectNode().put("visible", stage.isShowing());
            if (!Double.isNaN(stage.getX())) j.put("x", stage.getX()).put("y", stage.getY());
            Files.writeString(file, j.toString());
        } catch (IOException e) {
            System.err.println("ccacc: free widget position not saved: " + e.getMessage());
        }
    }
}
