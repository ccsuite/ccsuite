package ccsuite.ccacc;

import module java.base;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;

/**
 * The free accounts, and only they: one line each with the five-hour and the weekly gauge, the status, the time to the next reset, how old the
 * reading is and which model answered the probe. F5 reads every account again now; Esc closes. The numbers come from {@link FreeGauges}.
 */
final class FreeWindow {

    final FreeGauges gauges;
    final Stage stage = new Stage();
    final ListView<FreeGauges.Row> list = new ListView<>();
    final Label summary = new Label();
    final Timeline tick = new Timeline(new KeyFrame(Duration.seconds(20), e -> redraw()));

    FreeWindow(FreeGauges gauges, String color) {
        this.gauges = gauges;
        var title = new Label("Free accounts");
        title.setStyle("-fx-font-size: 18; -fx-font-weight: bold; -fx-text-fill: white;");
        var band = new VBox(title);
        band.setPadding(new Insets(8, 14, 8, 14));
        band.setStyle("-fx-background-color: " + color + ";");
        summary.setWrapText(true);
        summary.setStyle("-fx-text-fill: #555;");
        list.setCellFactory(v -> new ListCell<>() {
            @Override protected void updateItem(FreeGauges.Row r, boolean empty) {
                super.updateItem(r, empty);
                setText(empty || r == null ? null : FreeGauges.line(r, Instant.now()));
                setStyle("-fx-font-family: 'Consolas', 'Monospaced'; -fx-font-size: 13;" + (empty || r == null ? "" : colour(r)));
            }
        });
        var body = new VBox(10, list, summary);
        body.setPadding(new Insets(12, 14, 12, 14));
        VBox.setVgrow(list, javafx.scene.layout.Priority.ALWAYS);
        var scene = new Scene(new VBox(band, body), 1100, 360);
        VBox.setVgrow(body, javafx.scene.layout.Priority.ALWAYS);
        scene.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) stage.hide();
            else if (e.getCode() == KeyCode.F5) refresh();
        });
        stage.setTitle("ccacc free accounts");
        stage.getIcons().setAll(FxImages.windowIcons());
        stage.setScene(scene);
        tick.setCycleCount(Timeline.INDEFINITE);
        gauges.onChange = this::redrawLater;
    }

    static String colour(FreeGauges.Row r) {
        if (!r.ok()) return " -fx-text-fill: #a33;";
        if (r.five() >= 0.9) return " -fx-text-fill: #b3261e;";
        if (r.five() >= 0.7) return " -fx-text-fill: #a56a00;";
        return "";
    }

    void toggle() {
        if (stage.isShowing()) stage.hide(); else show();
    }

    void show() {
        redraw();
        tick.play();
        stage.show();
        stage.toFront();
        if (gauges.snapshot().isEmpty()) refresh();
    }

    void refresh() {
        summary.setText("Reading every free account now (one small message each)…");
        gauges.refreshInBackground();
    }

    void redrawLater() {
        Platform.runLater(this::redraw);
    }

    void redraw() {
        if (!stage.isShowing()) return;
        var rows = gauges.snapshot();
        list.getItems().setAll(rows);
        var free = FreeGauges.free(gauges.accounts.get()).size();
        var left = rows.stream().filter(FreeGauges.Row::ok).mapToDouble(r -> 1 - r.five()).sum();
        summary.setText(free == 0 ? "No free accounts saved (tier 0 with a web session key and an organisation id)."
            : free + " free accounts. About " + Math.round(left * 100) + "% of one five-hour window is left across the ones read. Free accounts have no usage endpoint: each reading is a one-word probe, kept in ~/ccsuite/ccacc/free-readings/. F5 reads again, Esc closes.");
    }
}
