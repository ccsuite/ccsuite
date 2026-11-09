package ccsuite.ccacc;

import module java.base;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/**
 * Gate and relay settings, on the Servers tab: the gate's port, the relay's workers and its address as a form, and the provider secret only as set or not (it is copied
 * on request and made anew on request, never shown). A refused value says why and changes nothing. They take effect when the gate or the relay next starts: switch
 * the part off and on above.
 */
final class GateSettingsPane extends VBox {

    final Parts parts;
    final RelayConfig relay;
    final TextField port = new TextField(), workers = new TextField(), url = new TextField();
    final Label secretState = new Label(), note = new Label();

    GateSettingsPane(Parts parts, RelayConfig relay) {
        super(8);
        this.parts = parts;
        this.relay = relay;
        var title = new Label("Gate and relay");
        title.setStyle("-fx-font-weight: bold; -fx-padding: 12 0 0 0;");
        var grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(7);
        grid.addRow(0, new Label("Gate port"), port);
        grid.addRow(1, new Label("Relay workers"), workers);
        grid.addRow(2, new Label("Relay address"), url);
        url.setPromptText("https://…");
        url.setPrefColumnCount(30);
        var apply = new Button("Apply");
        apply.setOnAction(e -> apply());
        var copy = new Button("Copy the secret");
        copy.setOnAction(e -> copySecret());
        var fresh = new Button("Make a new secret…");
        fresh.setOnAction(e -> newSecret());
        note.setWrapText(true);
        note.setStyle("-fx-text-fill: #8a3b1c;");
        getChildren().addAll(title, grid, new HBox(10, apply), new HBox(10, new Label("Provider secret:"), secretState, copy, fresh), note);
        setPadding(new Insets(0, 0, 6, 0));
    }

    /** The form from the settings as they are now. */
    void fill() {
        port.setText(String.valueOf(parts.settings.gatePort()));
        workers.setText(String.valueOf(parts.settings.relayWorkers()));
        try {
            url.setText(relay.url().orElse(""));
            secretState.setText(relay.secret().isPresent() ? "set (not shown)" : "none yet");
        } catch (IOException e) {
            note.setText("The relay files could not be read: " + e.getMessage());
        }
    }

    /** Saves what was changed; a refused value is named and the rest still applies. */
    void apply() {
        var said = new ArrayList<String>();
        var failed = new ArrayList<String>();
        var changed = false;
        var s = parts.settings;
        try {
            var p = whole(port.getText(), "the gate port");
            if (p < 1024 || p > 65535) throw new IllegalArgumentException("the gate port is a port between 1024 and 65535, not " + p);
            if (p != s.gatePort()) { s.gatePort(p); changed = true; said.add("gate port " + p); }
        } catch (IllegalArgumentException e) { failed.add(e.getMessage()); }
        try {
            var w = whole(workers.getText(), "the relay workers");
            if (w < 1 || w > 64) throw new IllegalArgumentException("the relay workers are a number from 1 to 64, not " + w);
            if (w != s.relayWorkers()) { s.relayWorkers(w); changed = true; said.add("relay workers " + w); }
        } catch (IllegalArgumentException e) { failed.add(e.getMessage()); }
        if (changed) parts.store.save(s);
        try {
            var current = relay.url().orElse("");
            if (!url.getText().strip().equals(current) && !url.getText().isBlank()) { relay.url(url.getText()); said.add("relay address"); }
        } catch (IllegalArgumentException | IOException e) { failed.add("the relay address: " + e.getMessage()); }
        note.setText((failed.isEmpty() ? "" : "Refused: " + String.join("; ", failed) + "\n")
                + (said.isEmpty() ? (failed.isEmpty() ? "Nothing was changed." : "") : "Saved: " + String.join(", ", said) + " (applies at the next start of the gate or the relay)"));
        fill();
    }

    static int whole(String text, String what) {
        try { return Integer.parseInt(text.strip()); }
        catch (NumberFormatException e) { throw new IllegalArgumentException(what + " is a whole number"); }
    }

    void copySecret() {
        try {
            var secret = relay.secret();
            if (secret.isEmpty()) { note.setText("There is no secret yet: make one first."); return; }
            var content = new ClipboardContent();
            content.putString(secret.get());
            Clipboard.getSystemClipboard().setContent(content);
            note.setText("The secret is on the clipboard.");
        } catch (IOException e) {
            note.setText("The secret could not be read: " + e.getMessage());
        }
    }

    /** Makes a new provider secret (after asking when there already is one: the relay server must be given the new one). */
    void newSecret() {
        try {
            if (relay.secret().isPresent()) {
                var ask = new Alert(Alert.AlertType.CONFIRMATION, "A secret exists. A new one replaces it, and the relay server must be given the new one.", ButtonType.OK, ButtonType.CANCEL);
                ask.setHeaderText("Make a new secret?");
                if (ask.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
            }
            relay.ensureSecret(true);
            note.setText("A new secret was made. Use Copy the secret to give it to the relay server.");
            fill();
        } catch (IOException e) {
            note.setText("The secret could not be made: " + e.getMessage());
        }
    }
}
