package ccsuite.ccacc;

import ccsuite.base.model.CCAPICredential;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;
import javafx.stage.Stage;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Optional;

/**
 * The account manager: the accounts on the left, what is set for the chosen one on the right (name, e-mail, the most
 * of it Claude Code may use before the watcher moves on, the tier, whether it takes part, is tracked, or is monitor
 * only), add and remove. Apply (F2) saves the form; nothing is saved before. Esc closes.
 */
final class AccountsWindow {

    final Parts parts;
    final Accounts accounts;
    final Stage stage = new Stage();
    final ListView<String> list = new ListView<>();
    final TextField name = new TextField(), email = new TextField(), limit = new TextField(), tier = new TextField();
    final CheckBox include = new CheckBox("Takes part (active)"), track = new CheckBox("Track its usage"),
            monitorOnly = new CheckBox("Monitor only: watched and counted in the totals, never switched to, never served from");
    final Label note = new Label();
    boolean filling;

    AccountsWindow(Parts parts, Accounts accounts, String color) {
        this.parts = parts;
        this.accounts = accounts;
        var title = new Label("Accounts");
        title.setStyle("-fx-font-size: 18; -fx-font-weight: bold; -fx-text-fill: white;");
        var band = new VBox(title);
        band.setPadding(new Insets(8, 14, 8, 14));
        band.setStyle("-fx-background-color: " + color + ";");

        list.setPrefWidth(190);
        list.getSelectionModel().selectedIndexProperty().addListener((o, was, now) -> fill());
        var grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(7);
        var row = 0;
        for (var f : new Object[][]{{"Name", name}, {"E-mail", email}, {"Limit (% of the quota Claude Code may use)", limit}, {"Tier (0 = free)", tier}})
            grid.addRow(row++, new Label((String) f[0]), (TextField) f[1]);
        limit.setPromptText("95");
        monitorOnly.setWrapText(true);
        var apply = new Button("Apply  (F2)");
        apply.setOnAction(e -> apply());
        var register = new Button("Save Claude Code's current login for this account…");
        register.setOnAction(e -> register());
        var add = new Button("Add account…");
        add.setOnAction(e -> add());
        var remove = new Button("Remove…");
        remove.setOnAction(e -> remove());
        note.setWrapText(true);
        note.setStyle("-fx-text-fill: #8a3b1c;");
        var form = new VBox(10, grid, include, track, monitorOnly, new HBox(10, apply), register, note);
        form.setPadding(new Insets(0, 0, 0, 14));
        HBox.setHgrow(form, Priority.ALWAYS);
        var body = new VBox(10, new HBox(0, list, form), new HBox(10, add, remove));
        body.setPadding(new Insets(12, 14, 12, 14));
        var scene = new Scene(new VBox(band, body), 700, 430);
        scene.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) stage.hide();
            else if (e.getCode() == KeyCode.F2) apply();
        });
        stage.setTitle("ccacc accounts");
        stage.getIcons().setAll(FxImages.windowIcons());
        stage.setScene(scene);
    }

    void show() {
        redraw(null);
        stage.show();
        stage.toFront();
    }

    /** The list from the file's accounts, keeping the chosen one when it is still there. */
    void redraw(String keep) {
        var rows = accounts.list().stream().map(AccountsWindow::row).toList();
        var at = keep == null ? list.getSelectionModel().getSelectedIndex() : idsNow().indexOf(keep);
        list.getItems().setAll(rows);
        list.getSelectionModel().select(rows.isEmpty() ? -1 : Math.max(0, Math.min(at, rows.size() - 1)));
        fill();
    }

    static String row(CCAPICredential c) {
        return "%-10s %s%s".formatted(c.id(), c.watchOnly() ? "monitor only" : "", c.active() ? "" : "  (off)");
    }

    java.util.List<String> idsNow() { return accounts.list().stream().map(CCAPICredential::id).toList(); }

    CCAPICredential chosen() {
        var i = list.getSelectionModel().getSelectedIndex();
        var all = accounts.list();
        return i < 0 || i >= all.size() ? null : all.get(i);
    }

    /** Puts the chosen account's settings into the form. */
    void fill() {
        var c = chosen();
        filling = true;
        try {
            for (var n : new javafx.scene.Node[]{name, email, limit, tier, include, track, monitorOnly}) n.setDisable(c == null);
            if (c == null) return;
            name.setText(c.name() == null ? "" : c.name());
            email.setText(c.email() == null ? "" : c.email());
            limit.setText(c.claudecodelimit().replace("%", ""));
            tier.setText(String.valueOf(c.tier()));
            include.setSelected(c.active());
            track.setSelected(c.trackUsage());
            monitorOnly.setSelected(c.watchOnly());
        } finally {
            filling = false;
        }
    }

    void apply() {
        var c = chosen();
        if (c == null || filling) return;
        var p = new LinkedHashMap<String, String>();
        p.put("claudecodelimit", limit.getText());
        p.put("tier", tier.getText());
        p.put("name", name.getText());
        p.put("email", email.getText());
        p.put("active", include.isSelected() ? "on" : "off");
        p.put("trackUsage", track.isSelected() ? "on" : "off");
        p.put("watchOnly", monitorOnly.isSelected() ? "on" : "off");
        try {
            accounts.set(c.id(), p);
            note.setText("Saved " + c.id() + ".");
        } catch (IllegalArgumentException | IOException e) {
            note.setText(e.getMessage());
        }
        redraw(c.id());
    }

    void add() {
        var dialog = new Dialog<ButtonType>();
        dialog.setTitle("Add account");
        var id = new TextField(); var n = new TextField(); var mail = new TextField(); var lim = new TextField("95");
        var grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(7);
        grid.setPadding(new Insets(12));
        grid.addRow(0, new Label("Id (short, e.g. CAT)"), id);
        grid.addRow(1, new Label("Name"), n);
        grid.addRow(2, new Label("E-mail of the Claude account"), mail);
        grid.addRow(3, new Label("Limit %"), lim);
        grid.add(new Label("Afterwards log into the account in Claude Code and use\n\"Save Claude Code's current login\" so ccacc can switch to it."), 0, 4, 2, 1);
        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        Optional<ButtonType> answer = dialog.showAndWait();
        if (answer.isEmpty() || answer.get() != ButtonType.OK) return;
        var p = new LinkedHashMap<String, String>();
        p.put("id", id.getText().trim());
        p.put("name", n.getText());
        p.put("email", mail.getText());
        p.put("claudecodelimit", lim.getText());
        p.put("trackUsage", "on");
        p.put("tier", "1");
        try {
            accounts.add(p);
            note.setText("Added " + p.get("id") + ".");
            redraw(p.get("id"));
        } catch (IllegalArgumentException | IOException e) {
            note.setText(e.getMessage());
        }
    }

    void remove() {
        var c = chosen();
        if (c == null) return;
        var ask = new Alert(Alert.AlertType.CONFIRMATION, "Remove " + c.id() + " from ccacc? Its saved Claude Code login stays on disk.", ButtonType.OK, ButtonType.CANCEL);
        ask.setHeaderText(null);
        if (ask.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        try {
            accounts.remove(c.id());
            note.setText("Removed " + c.id() + ".");
        } catch (IllegalArgumentException | IOException e) {
            note.setText(e.getMessage());
        }
        redraw(null);
    }

    /** Saves the login Claude Code holds right now as this account's, so ccacc can switch to it later. */
    void register() {
        var c = chosen();
        if (c == null) return;
        var ask = new Alert(Alert.AlertType.CONFIRMATION, "Is Claude Code logged into the account " + c.id() + " (" + c.email() + ") right now? Its login will be saved for switching.", ButtonType.OK, ButtonType.CANCEL);
        ask.setHeaderText(null);
        if (ask.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        try {
            parts.core.swap.register(Optional.of(c.id()));
            note.setText("Saved the current Claude Code login for " + c.id() + ".");
        } catch (RuntimeException | IOException e) {
            note.setText(String.valueOf(e.getMessage()));
        }
    }
}
