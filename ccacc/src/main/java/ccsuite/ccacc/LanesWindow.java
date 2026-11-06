package ccsuite.ccacc;

import module java.base;
import ccsuite.localserver.lanes.Routed;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;

/**
 * The lanes: the two ports (pclaude, paid; fclaude, free), the state of each lane, and the last requests with the account that served each.
 * O switches the lanes on or off; Esc closes.
 */
final class LanesWindow {

    final LaneHost host;
    final Stage stage = new Stage();
    final Label state = new Label();
    final ListView<Routed> list = new ListView<>();
    final Timeline tick = new Timeline(new KeyFrame(Duration.seconds(5), e -> redraw()));

    LanesWindow(LaneHost host, String color) {
        this.host = host;
        var title = new Label("Lanes");
        title.setStyle("-fx-font-size: 18; -fx-font-weight: bold; -fx-text-fill: white;");
        var band = new VBox(title);
        band.setPadding(new Insets(8, 14, 8, 14));
        band.setStyle("-fx-background-color: " + color + ";");
        state.setStyle("-fx-font-family: 'Consolas', 'Monospaced'; -fx-font-size: 13;");
        var hint = new Label("O lanes on or off; Esc closes. Last requests, newest first:");
        hint.setWrapText(true);
        hint.setStyle("-fx-text-fill: #555;");
        list.setCellFactory(v -> new ListCell<>() {
            @Override protected void updateItem(Routed r, boolean empty) {
                super.updateItem(r, empty);
                setText(empty || r == null ? null : r.at().atZone(ZoneId.systemDefault()).toLocalTime().withNano(0) + "  " + r.line());
                setStyle("-fx-font-family: 'Consolas', 'Monospaced'; -fx-font-size: 12;" + (empty || r == null || r.status() < 400 ? "" : " -fx-text-fill: #a33;"));
            }
        });
        var body = new VBox(10, state, hint, list);
        body.setPadding(new Insets(12, 14, 12, 14));
        VBox.setVgrow(list, Priority.ALWAYS);
        var scene = new Scene(new VBox(band, body), 1200, 520);
        VBox.setVgrow(body, Priority.ALWAYS);
        scene.setOnKeyPressed(e -> {
            switch (e.getCode()) {
                case ESCAPE -> stage.hide();
                case O -> { host.enable(!host.running()); redraw(); }
                default -> { }
            }
        });
        stage.setTitle("ccacc lanes");
        stage.getIcons().setAll(FxImages.windowIcons());
        stage.setScene(scene);
        tick.setCycleCount(Timeline.INDEFINITE);
        host.service.onRouted = this::redrawLater;
    }

    void toggle() {
        if (stage.isShowing()) stage.hide(); else show();
    }

    void show() {
        redraw();
        tick.play();
        stage.show();
        stage.toFront();
    }

    void redrawLater() { Platform.runLater(this::redraw); }

    void redraw() {
        if (!stage.isShowing()) return;
        state.setText(String.join("\n", host.report()));
        list.getItems().setAll(host.service.recent());
    }
}
