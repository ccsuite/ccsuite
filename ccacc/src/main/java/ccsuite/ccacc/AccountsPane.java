package ccsuite.ccacc;

import ccsuite.base.model.CCAPICredential;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Optional;

/**
 * The Manage tab, the account editor: the accounts on the left (paid and free, each marked), what is set for the chosen one on the right (name, e-mail, the most
 * of it Claude Code may use before the watcher moves on, the tier, whether it takes part, is tracked, or is monitor only), add (paid or free) and remove.
 * Apply (F2) saves the form; nothing is saved before. Free accounts are tier 0 and are shown in the free views only.
 */
final class AccountsPane extends VBox implements Page {

    final Parts parts;
    final Accounts accounts;
    final ListView<String> list = new ListView<>();
    final TextField name = new TextField(), email = new TextField(), limit = new TextField(), tier = new TextField(), orgId = new TextField();
    final TextField key = new TextField();   // visible on purpose: the user wants to see the key it holds
    final CheckBox include = new CheckBox("Takes part (active)"), track = new CheckBox("Track its usage"),
            monitorOnly = new CheckBox("Monitor only: watched and counted in the totals, never switched to, never served from"),
            primary = new CheckBox("Primary: mine alone. No 5-hour guard, and in the last day of its week it is used up to 100%");
    final Label note = new Label();
    boolean filling, active;

    /** {@code accounts} is null when the accounts are not loaded (a test, or a machine with none yet): the tab then only says so. */
    AccountsPane(Parts parts, Accounts accounts) {
        super(10);
        this.parts = parts;
        this.accounts = accounts;
        setPadding(new Insets(12, 14, 10, 14));
        list.setPrefWidth(230);
        list.setStyle("-fx-font-family: 'Consolas'; -fx-font-size: 13;");
        list.getSelectionModel().selectedIndexProperty().addListener((o, was, now) -> fill());
        var grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(7);
        var row = 0;
        for (var f : new Object[][]{{"Name", name}, {"E-mail", email}, {"Limit (% of the quota Claude Code may use)", limit}, {"Tier (0 = free)", tier}, {"Organisation id", orgId}, {"Session key", key}})
            grid.addRow(row++, new Label((String) f[0]), (TextField) f[1]);
        limit.setPromptText("95");
        monitorOnly.setWrapText(true);
        primary.setWrapText(true);
        var apply = new Button("Apply  (F2)");
        apply.setOnAction(e -> apply());
        var register = new Button("Save Claude Code's current login for this account…");
        register.setOnAction(e -> register());
        var add = new Button("Add account…");
        add.setOnAction(e -> add());
        var addFree = new Button("Add free account…");
        addFree.setOnAction(e -> addFree());
        var remove = new Button("Remove…");
        remove.setOnAction(e -> remove());
        note.setWrapText(true);
        note.setStyle("-fx-text-fill: #8a3b1c;");
        var form = new VBox(10, grid, include, track, monitorOnly, primary, new HBox(10, apply), register);
        form.setPadding(new Insets(0, 0, 0, 14));
        HBox.setHgrow(form, Priority.ALWAYS);
        var split = new HBox(0, list, form);
        VBox.setVgrow(split, Priority.ALWAYS);
        getChildren().addAll(split, new HBox(10, add, addFree, remove), note);
    }

    @Override public void start() {
        active = true;
        if (accounts == null) { note.setText("The accounts are not loaded."); return; }
        redraw(null);
    }

    @Override public void stop() { active = false; }

    @Override public java.util.List<KeyAction> keys() { return java.util.List.of(new KeyAction(KeyCode.F2, "Apply", this::apply)); }

    /** The list from the file's accounts, keeping the chosen one when it is still there. */
    void redraw(String keep) {
        if (accounts == null) return;
        var rows = accounts.list().stream().map(AccountsPane::row).toList();
        var at = keep == null ? list.getSelectionModel().getSelectedIndex() : idsNow().indexOf(keep);
        list.getItems().setAll(rows);
        list.getSelectionModel().select(rows.isEmpty() ? -1 : Math.max(0, Math.min(at, rows.size() - 1)));
        fill();
    }

    static String row(CCAPICredential c) {
        var kind = c.tier() == 0 ? "free" : c.watchOnly() ? "monitor only" : c.primary() ? "primary" : "";
        return "%-10s %s%s".formatted(c.id(), kind, c.active() || c.tier() == 0 ? "" : "  (off)");   // the flag plays no part for a free account
    }

    java.util.List<String> idsNow() { return accounts == null ? java.util.List.of() : accounts.list().stream().map(CCAPICredential::id).toList(); }

    CCAPICredential chosen() {
        var i = list.getSelectionModel().getSelectedIndex();
        var all = accounts == null ? java.util.List.<CCAPICredential>of() : accounts.list();
        return i < 0 || i >= all.size() ? null : all.get(i);
    }

    /** Puts the chosen account's settings into the form. */
    void fill() {
        var c = chosen();
        filling = true;
        try {
            for (var n : new javafx.scene.Node[]{name, email, limit, tier, orgId, key, include, track, monitorOnly, primary}) n.setDisable(c == null);
            if (c == null) return;
            name.setText(c.name() == null ? "" : c.name());
            email.setText(c.email() == null ? "" : c.email());
            limit.setText(c.claudecodelimit().replace("%", ""));
            tier.setText(String.valueOf(c.tier()));
            include.setSelected(c.active());
            track.setSelected(c.trackUsage());
            monitorOnly.setSelected(c.watchOnly());
            primary.setSelected(c.primary());
            orgId.setText(c.orgId() == null ? "" : c.orgId());
            key.setText(c.key() == null ? "" : c.key());
            key.setPromptText("none saved");
            var free = c.tier() == 0;   // a free account is read by probe and its state follows its restore time: these play no part
            for (var n : new javafx.scene.Node[]{include, track, primary, limit}) n.setDisable(free);
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
        p.put("primary", primary.isSelected() ? "on" : "off");
        var typed = key.getText().strip();
        if (typed.matches(".*\\s.*")) { note.setText("The session key has no spaces or line breaks: paste only the key."); return; }
        if (!orgId.getText().isBlank()) p.put("orgId", orgId.getText());
        if (!typed.isBlank() && !typed.equals(c.key())) p.put("key", typed);
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

    void addFree() {
        if (accounts == null) return;
        FreeAccountDialog.show(accounts).ifPresent(id -> { note.setText("Added free account " + id + "."); redraw(id); });
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
