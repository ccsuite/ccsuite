package ccsuite.ccacc;

import module java.base;
import ccsuite.localserver.lanes.Routed;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * The Lanes tab: the switch, a few lines of state, and three sub-tabs. Requests: the log of the last requests with the account that served each (newest first).
 * Settings: the lane settings as a form ({@link LaneSettingsPane}). Calls report: what the lanes served and what it cost ({@link CallsPane}). The accounts' usage
 * is not repeated here: it is in the Paid and Free tabs. Built when first shown; the log follows the lanes while in front; the form and the report are read when their
 * sub-tab comes forward.
 */
final class LanesPane extends VBox implements Page {

    final Parts parts;
    final CheckBox on = new CheckBox("Lanes on"), hold = new CheckBox("Hold new requests (the free lane answers 503 until released)");
    final Label state = new Label();
    final ListView<Routed> list = new ListView<>();
    final LaneSettingsPane settings;
    final CallsPane calls;
    final TabPane sub = new TabPane();
    final Tab requestsTab, settingsTab, callsTab;
    boolean active, redrawing, wired;

    LanesPane(Parts parts) {
        super(8);
        this.parts = parts;
        settings = new LaneSettingsPane(parts);
        calls = new CallsPane(parts);
        setPadding(new Insets(12, 14, 10, 14));
        state.setStyle("-fx-font-family: 'Consolas', 'Monospaced'; -fx-font-size: 13;");
        state.setMinHeight(Region.USE_PREF_SIZE);   // several lines: a label would shrink to one when the window is short
        list.setCellFactory(v -> new ListCell<>() {
            @Override protected void updateItem(Routed r, boolean empty) {
                super.updateItem(r, empty);
                setText(empty || r == null ? null : r.at().atZone(ZoneId.systemDefault()).toLocalTime().withNano(0) + "  " + r.line());
                setStyle("-fx-font-family: 'Consolas', 'Monospaced'; -fx-font-size: 12;" + (empty || r == null || r.status() < 400 ? "" : " -fx-text-fill: #a33;"));
            }
        });
        var hint = new Label("Last requests, newest first:");
        hint.setStyle("-fx-text-fill: #555;");
        VBox.setVgrow(list, Priority.ALWAYS);
        requestsTab = tab("Requests", new VBox(6, hint, list));
        var scroll = new ScrollPane(settings);
        scroll.setFitToWidth(true);   // the form is taller than a small window: its last rows and the Apply button must stay reachable
        settingsTab = tab("Settings", scroll);
        callsTab = tab("Calls report", calls);
        VBox.setVgrow(sub, Priority.ALWAYS);
        sub.getSelectionModel().selectedItemProperty().addListener((o, was, now) -> subShown());
        getChildren().addAll(new HBox(20, on, hold), state, sub);
    }

    Tab tab(String text, javafx.scene.Node content) {
        var t = new Tab(text, content);
        t.setClosable(false);
        sub.getTabs().add(t);
        return t;
    }

    /** The sub-tab a command-line name means (requests, settings, calls), or null. */
    Tab subNamed(String name) {
        return switch (name == null ? "" : name.toLowerCase()) {
            case "requests" -> requestsTab;
            case "settings" -> settingsTab;
            case "calls" -> callsTab;
            default -> null;
        };
    }

    /** The sub-tab now in front reads what it shows: the form the settings as they are, the report the logs (once). */
    void subShown() {
        if (!active) return;
        var front = sub.getSelectionModel().getSelectedItem();
        if (front == settingsTab) settings.fill();
        if (front == callsTab) calls.loadOnce();
    }

    @Override public void start() {
        active = true;
        var host = parts.lanes;
        if (host == null) { state.setText("The lanes are not running here; the settings below are the settings file."); on.setDisable(true); hold.setDisable(true); subShown(); return; }
        if (!wired) {
            wired = true;
            on.setOnAction(e -> { if (!redrawing) { host.enable(on.isSelected()); redraw(); } });
            hold.setOnAction(e -> { if (!redrawing) { host.hold(hold.isSelected()); redraw(); } });
            host.service.onRouted = () -> Platform.runLater(this::redraw);
        }
        redraw();
        subShown();
    }

    @Override public void stop() { active = false; }

    @Override public List<KeyAction> keys() {
        return List.of(new KeyAction(KeyCode.F2, "Apply settings", () -> { if (sub.getSelectionModel().getSelectedItem() == settingsTab) settings.apply(); }),
            new KeyAction(KeyCode.F3, "Lanes on/off", on::fire), new KeyAction(KeyCode.F4, "Hold", hold::fire),
            new KeyAction(KeyCode.F5, "Load report", () -> { if (sub.getSelectionModel().getSelectedItem() == callsTab) calls.load(); }));
    }

    void redraw() {
        var host = parts.lanes;
        if (!active || host == null) return;
        redrawing = true;
        try {
            on.setSelected(host.running());
            var f = host.free;
            hold.setDisable(f == null);
            hold.setSelected(f != null && f.hold);
            state.setText(String.join("\n", host.summary()));
            list.getItems().setAll(host.service.recent());
        } finally {
            redrawing = false;
        }
    }
}
