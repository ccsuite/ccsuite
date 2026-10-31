package ccsuite.ccacc;

import ccsuite.usagetracker.ui.UsageWidget;
import ccsuite.oauthswap.watch.WatchMode;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;
import javafx.stage.Stage;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static ccsuite.ccacc.TrayText.*;

/**
 * The simple window, in two tabs. Accounts: the accounts with their usage, a button to switch to the chosen one,
 * the watcher's mode, the on/off switches of the parts, and the way into the account manager. Total: all accounts
 * as one (the averages the taskbar card shows on its ALL page). The tray menu stays for the quick things.
 * Keyboard first: Up and Down choose, Enter switches, Esc closes. Redrawn when the watcher polls and when it is
 * shown, never on a timer (a resident app must not keep JavaFX's frame clock running).
 */
final class MainWindow {

    static final String MONO = "-fx-font-family: 'Consolas'; -fx-font-size: 13;";

    final Parts parts;
    final WatchBridge bridge;
    final Supplier<String> statusText;
    final String color;
    final Stage stage = new Stage();
    final Label status = new Label();
    final Label note = new Label();
    final Label total = new Label();
    final ListView<String> accounts = new ListView<>();
    final List<String> ids = new ArrayList<>();
    final ToggleGroup modes = new ToggleGroup();
    final CheckBox watch = new CheckBox("Account watcher"), widget = new CheckBox("Usage widget"),
            card = new CheckBox("Card in the taskbar"), server = new CheckBox("Local server");
    AccountsWindow manager;
    boolean redrawing;

    MainWindow(Parts parts, WatchBridge bridge, String color, Supplier<String> statusText) {
        this.parts = parts;
        this.bridge = bridge;
        this.statusText = statusText;
        this.color = color;
        var title = new Label("ccacc");
        title.setStyle("-fx-font-size: 20; -fx-font-weight: bold; -fx-text-fill: white;");
        status.setStyle("-fx-text-fill: white;");
        var band = new VBox(2, title, status);
        band.setPadding(new Insets(10, 14, 10, 14));
        band.setStyle("-fx-background-color: " + color + ";");

        var tabs = new TabPane(tab("Accounts", accountsPage()), tab("Total", totalPage()));
        var scene = new Scene(new VBox(band, tabs), 600, 520);
        scene.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) stage.hide();
            else if (e.getCode() == KeyCode.ENTER && accounts.isFocused()) switchToChosen();
        });
        stage.setTitle("ccacc");
        stage.getIcons().setAll(FxImages.windowIcons());
        stage.setScene(scene);
    }

    static Tab tab(String text, javafx.scene.Node content) {
        var t = new Tab(text, content);
        t.setClosable(false);
        return t;
    }

    VBox accountsPage() {
        accounts.setStyle(MONO);
        accounts.setPrefHeight(190);
        var switchButton = new Button("Switch to chosen account  (Enter)");
        switchButton.setOnAction(e -> switchToChosen());
        var refresh = new Button("Refresh now");
        refresh.setOnAction(e -> { if (parts.watcher != null) parts.watcher.pollNow(); });
        var manage = new Button("Manage accounts…");
        manage.setOnAction(e -> openManager());

        var modeRow = new HBox(14, new Label("Mode:"));
        for (var m : WatchMode.values()) {
            var b = new RadioButton(modeText(m));
            b.setUserData(m);
            b.setToggleGroup(modes);
            modeRow.getChildren().add(b);
        }
        modes.selectedToggleProperty().addListener((o, was, now) -> { if (!redrawing && now != null) parts.mode((WatchMode) now.getUserData()); });
        wire(watch, parts::watch);
        wire(widget, parts::widget);
        wire(card, on -> { if (parts.widget != null) parts.widget.taskbar(on); });
        card.setVisible(UsageWidget.WINDOWS);
        card.setManaged(UsageWidget.WINDOWS);
        wire(server, parts::server);

        note.setWrapText(true);
        note.setStyle("-fx-text-fill: #8a3b1c;");
        var legend = new Label("↑/↓ choose · Enter switch · Esc close");
        legend.setStyle("-fx-font-size: 10; -fx-text-fill: #888;");
        var page = new VBox(10, accounts, new HBox(10, switchButton, refresh, manage), modeRow, new HBox(16, watch, widget, card, server), note, legend);
        page.setPadding(new Insets(12, 14, 10, 14));
        return page;
    }

    VBox totalPage() {
        total.setStyle(MONO);
        var page = new VBox(10, total);
        page.setPadding(new Insets(16, 14, 10, 14));
        return page;
    }

    void wire(CheckBox box, Consumer<Boolean> action) {
        box.setOnAction(e -> { if (!redrawing) { action.accept(box.isSelected()); redraw(); } });
    }

    void openManager() {
        if (parts.core == null) { note.setText("The accounts are not loaded."); return; }
        if (manager == null) manager = new AccountsWindow(parts, new Accounts(parts.core.credentials, parts.core.creds, this::accountsChanged), color);
        manager.show();
    }

    /** An account was added, removed or changed: poll again so the numbers follow, and redraw. */
    void accountsChanged() {
        if (parts.watcher != null) parts.watcher.pollNow();
        redrawIfShowing();
    }

    void toggle() {
        if (stage.isShowing()) stage.hide(); else show();
    }

    void show() {
        redraw();
        stage.show();
        stage.toFront();
        accounts.requestFocus();
    }

    void redrawIfShowing() {
        if (stage.isShowing()) redraw();
    }

    /** Puts what the parts say now into the controls, without those controls acting on it. */
    void redraw() {
        redrawing = true;
        try {
            var w = parts.watcher;
            status.setText(statusText.get());
            var chosen = accounts.getSelectionModel().getSelectedIndex();
            ids.clear();
            var rows = new ArrayList<String>();
            bridge.usages.forEach((id, u) -> {
                ids.add(id);
                rows.add("%s %-12s 5h %-5s week %-5s avg %-6s free %s".formatted(id.equals(bridge.activeId) ? "●" : " ", id, fiveHour(u), week(u), avg(u), weekFree(u)));
            });
            accounts.getItems().setAll(rows);
            if (chosen >= 0 && chosen < rows.size()) accounts.getSelectionModel().select(chosen);
            modes.getToggles().forEach(t -> t.setSelected(w != null && t.getUserData() == w.mode()));
            watch.setSelected(w != null);
            widget.setSelected(parts.widget != null);
            card.setDisable(parts.widget == null);
            card.setSelected(parts.widget != null && parts.widget.taskbarOn());
            server.setSelected(parts.server.running());
            note.setText(parts.server.running() || parts.serverProblem == null ? "" : "The local server did not start: " + parts.serverProblem);
            total.setText(totalText(w == null ? null : w.mode()));
        } finally {
            redrawing = false;
        }
    }

    /** The Total tab: all accounts as one, and whether the watcher switches on its own. */
    String totalText(WatchMode mode) {
        var c = Totals.of(bridge.usages);
        return String.join("\n",
                "Accounts counted      5-hour " + c.fiveHourAccounts() + "     weekly " + c.weekAccounts(),
                "",
                "5-hour   usage " + num(c.hasFiveHour(), c.fiveHourUsage()) + "   time gone " + num(c.hasFiveHour(), c.fiveHourTime()) + "   rate " + num(c.hasFiveHour(), c.fiveHourRate()),
                "Weekly   usage " + num(c.hasWeek(), c.weekUsage()) + "   time gone " + num(c.hasWeek(), c.weekTime()) + "   rate " + num(c.hasWeek(), c.weekRate()),
                "",
                "Free     5-hour " + num(c.hasFiveHour(), 100 - c.fiveHourUsage()) + "   weekly " + num(c.hasWeek(), 100 - c.weekUsage()),
                "",
                "Switching: " + switching(mode),
                "",
                "Every account counts the same. Rate is usage over time gone: 100% spends",
                "the window exactly as it passes, above that is faster.");
    }

    static String num(boolean known, double v) { return known ? pct(v) : "--"; }

    static String switching(WatchMode m) {
        if (m == null) return "off (the account watcher is not running)";
        return m == WatchMode.AUTO ? "auto: ccacc moves Claude Code between accounts on its own" : "off: Claude Code stays on one account";
    }

    void switchToChosen() {
        var i = accounts.getSelectionModel().getSelectedIndex();
        if (i < 0 || i >= ids.size()) { note.setText("Choose an account first."); return; }
        if (parts.watcher == null) { note.setText("Turn the account watcher on to switch accounts."); return; }
        if (ids.get(i).equals(bridge.activeId)) { note.setText(ids.get(i) + " is already the active account."); return; }
        parts.watcher.requestSwitch(ids.get(i));
        note.setText("Switching to " + ids.get(i) + "…");
    }

    static String modeText(WatchMode m) {
        return switch (m) {
            case AUTO -> "Auto";
            case LOCKED -> "Locked (stay, still warn)";
            case PAUSED -> "Paused (no polling)";
        };
    }
}
