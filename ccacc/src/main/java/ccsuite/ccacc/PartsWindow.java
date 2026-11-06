package ccsuite.ccacc;

import module java.base;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;

/**
 * What used to make the tray menu twenty rows tall: switching to another account, and the parts and servers with their
 * switches. Each row is the same row the menu used to have ({@link TrayItems#switchItems()} and {@code partItems()}), shown as a button; a click does it
 * and the window redraws. Esc closes.
 */
final class PartsWindow {

    final TrayItems items;
    final Stage stage = new Stage();
    final VBox body = new VBox(4);
    final Timeline tick = new Timeline(new KeyFrame(Duration.seconds(5), e -> redraw()));

    PartsWindow(TrayItems items, String color) {
        this.items = items;
        var title = new Label("Accounts, parts and servers");
        title.setStyle("-fx-font-size: 18; -fx-font-weight: bold; -fx-text-fill: white;");
        var band = new VBox(title);
        band.setPadding(new Insets(8, 14, 8, 14));
        band.setStyle("-fx-background-color: " + color + ";");
        body.setPadding(new Insets(10, 14, 12, 14));
        var scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        var scene = new Scene(new VBox(band, scroll), 560, 620);
        scene.setOnKeyPressed(e -> { if (e.getCode() == KeyCode.ESCAPE) stage.hide(); });
        stage.setTitle("ccacc accounts, parts and servers");
        stage.getIcons().setAll(FxImages.windowIcons());
        stage.setScene(scene);
        tick.setCycleCount(Timeline.INDEFINITE);
    }

    void toggle() {
        if (stage.isShowing()) { tick.stop(); stage.hide(); } else show();
    }

    void show() {
        redraw();
        tick.play();
        stage.show();
        stage.toFront();
    }

    void redraw() {
        if (!stage.isShowing()) return;
        var nodes = new ArrayList<javafx.scene.Node>();
        section(nodes, "Switch to another account", items.switchItems(), "No other account, or the account watcher is off.");
        section(nodes, "Parts and servers", items.partItems(), "");
        body.getChildren().setAll(nodes);
    }

    void section(List<javafx.scene.Node> out, String head, List<MenuItem> rows, String empty) {
        var h = new Label(head);
        h.setStyle("-fx-font-weight: bold; -fx-text-fill: #555; -fx-padding: 8 0 2 0;");
        out.add(h);
        if (rows.isEmpty()) { var l = new Label(empty); l.setStyle("-fx-text-fill: #888;"); out.add(l); return; }
        rows.forEach(m -> out.add(button(m)));
    }

    Button button(MenuItem m) {
        var b = new Button(m.getText());
        b.setMaxWidth(Double.MAX_VALUE);
        b.setAlignment(Pos.CENTER_LEFT);
        b.setStyle("-fx-font-family: 'Consolas', 'Monospaced'; -fx-font-size: 13;");
        b.setOnAction(e -> { m.fire(); javafx.application.Platform.runLater(this::redraw); });
        return b;
    }
}
