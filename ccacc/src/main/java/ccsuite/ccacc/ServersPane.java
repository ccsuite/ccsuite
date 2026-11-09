package ccsuite.ccacc;

import module java.base;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import static ccsuite.usagetracker.ui.UsageWidget.WINDOWS;

/**
 * The Servers tab: every part of the app as a switch with its state in words: the account watcher, the usage widget (its taskbar card and its floating window),
 * the local server, the gate and the relay. What the tray menu's long list used to be. The lanes have their own tab. Redrawn every five seconds while in front.
 */
final class ServersPane extends VBox implements Page {

    final Parts parts;
    final TrayItems items;
    final CheckBox watch = new CheckBox("Account watcher"), widget = new CheckBox("Usage widget"), card = new CheckBox("Card in the taskbar"),
            floating = new CheckBox("Floating usage window"), server = new CheckBox("Local server"), gate = new CheckBox("Gate"), relay = new CheckBox("Relay");
    final Label watchNote = note(), widgetNote = note(), serverNote = note(), gateNote = note(), relayNote = note();
    final GateSettingsPane gateSettings;
    final Timeline tick = new Timeline(new KeyFrame(Duration.seconds(5), e -> redraw()));
    boolean active, redrawing;

    ServersPane(Parts parts, TrayItems items) {
        super(10);
        this.parts = parts;
        this.items = items;
        setPadding(new Insets(16, 14, 10, 14));
        var grid = new GridPane();
        grid.setHgap(16);
        grid.setVgap(10);
        var row = 0;
        grid.addRow(row++, watch, watchNote);
        grid.addRow(row++, widget, widgetNote);
        grid.addRow(row++, indent(card), new Label());
        grid.addRow(row++, indent(floating), new Label());
        grid.addRow(row++, server, serverNote);
        grid.addRow(row++, gate, gateNote);
        grid.addRow(row, relay, relayNote);
        card.setVisible(WINDOWS);
        card.setManaged(WINDOWS);
        gateSettings = new GateSettingsPane(parts, RelayConfig.standard());
        getChildren().addAll(grid, gateSettings);
        wire(watch, parts::watch);
        wire(widget, parts::widget);
        wire(card, on -> { if (parts.widget != null) parts.widget.taskbar(on); });
        wire(floating, on -> { if (parts.widget != null) parts.widget.floating(on); });
        wire(server, parts::server);
        wire(gate, parts::gate);
        wire(relay, parts::relay);
        tick.setCycleCount(Timeline.INDEFINITE);
    }

    static Label note() {
        var l = new Label();
        l.setStyle("-fx-text-fill: #666;");
        return l;
    }

    static CheckBox indent(CheckBox b) {
        b.setStyle("-fx-padding: 0 0 0 24;");
        return b;
    }

    void wire(CheckBox box, Consumer<Boolean> action) {
        box.setOnAction(e -> { if (!redrawing) { action.accept(box.isSelected()); redraw(); } });
    }

    @Override public void start() {
        active = true;
        gateSettings.fill();
        redraw();
        tick.play();
    }

    @Override public void stop() {
        active = false;
        tick.stop();
    }

    void redraw() {
        if (!active) return;
        redrawing = true;
        try {
            var w = parts.widget;
            watch.setSelected(parts.watcher != null);
            watchNote.setText(parts.watcher == null ? "off: Claude Code stays where it is, nothing is read" : "mode " + parts.watcher.mode().name().toLowerCase());
            widget.setSelected(w != null);
            widgetNote.setText(w == null ? "off" : "the taskbar card and the usage window");
            card.setDisable(w == null);
            card.setSelected(w != null && w.taskbarOn());
            floating.setDisable(w == null);
            floating.setSelected(w != null && w.floatingOn());
            server.setSelected(parts.server.running());
            serverNote.setText(items.serverNote().strip());
            gate.setSelected(parts.gate.running());
            gateNote.setText(items.gateNote().strip());
            relay.setSelected(parts.relay != null);
            relayNote.setText(items.relayNote().strip());
        } finally {
            redrawing = false;
        }
    }
}
