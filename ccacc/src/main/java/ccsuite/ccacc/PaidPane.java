package ccsuite.ccacc;

import module java.base;
import ccsuite.oauthswap.watch.WatchMode;
import ccsuite.usagetracker.model.LastReadings;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;

/**
 * The Paid Accounts tab: ALL first, then every paid account as graphs ({@link UsageView}), with the one controls that belong to them: a button on each account to
 * switch Claude Code to it (after a confirmation), the watcher's mode, reading now, and the floating usage window. It replaces the old Accounts, Usage and Total
 * tabs. Free accounts never appear here. Drawn from the watcher's last poll; F5 polls again.
 */
final class PaidPane extends VBox implements Page {

    final Parts parts;
    final WatchBridge bridge;
    final UsageView view = new UsageView("#1e88e5");
    final ToggleGroup modes = new ToggleGroup();
    final Label switching = new Label(), note = new Label(), recommendText = new Label();
    final Button recommendButton = new Button();
    final HBox recommend = new HBox(10, recommendText, recommendButton);
    /** Shows or hides the floating paid usage window; set by the main window. */
    Runnable onDialog = () -> {};
    boolean active, redrawing;

    PaidPane(Parts parts, WatchBridge bridge) {
        super(8);
        this.parts = parts;
        this.bridge = bridge;
        setPadding(new Insets(12, 14, 10, 14));
        var modeRow = new HBox(14, new Label("Mode:"));
        for (var m : WatchMode.values()) {
            var b = new RadioButton(modeText(m));
            b.setUserData(m);
            b.setToggleGroup(modes);
            modeRow.getChildren().add(b);
        }
        modes.selectedToggleProperty().addListener((o, was, now) -> { if (!redrawing && now != null) parts.mode((WatchMode) now.getUserData()); });
        var read = new Button("Read now  (F5)");
        read.setOnAction(e -> readNow());
        var dialog = new Button("Floating window  (F9)");
        dialog.setOnAction(e -> onDialog.run());
        modeRow.getChildren().addAll(read, dialog);
        switching.setStyle("-fx-text-fill: #555;");
        note.setWrapText(true);
        note.setStyle("-fx-text-fill: #8a3b1c;");
        VBox.setVgrow(view, Priority.ALWAYS);
        view.decorate = this::decorate;
        recommend.setStyle("-fx-background-color: #fff3cd; -fx-padding: 6 10 6 10; -fx-alignment: center-left;");
        recommend.setVisible(false);
        recommend.setManaged(false);
        recommendButton.setOnAction(e -> { var t = bridge.pendingTarget; if (t != null) SwitchConfirm.ask(parts, t); });
        getChildren().addAll(modeRow, recommend, switching, view, note);
    }

    @Override public void start() { active = true; redraw(); }

    @Override public List<KeyAction> keys() {
        return List.of(new KeyAction(KeyCode.F5, "Read now", this::readNow), new KeyAction(KeyCode.F6, "Auto", () -> choose(WatchMode.AUTO)),
            new KeyAction(KeyCode.F7, "Locked", () -> choose(WatchMode.LOCKED)), new KeyAction(KeyCode.F8, "Paused", () -> choose(WatchMode.PAUSED)),
            new KeyAction(KeyCode.F9, "Floating window", () -> onDialog.run()));
    }

    void choose(WatchMode m) {
        modes.getToggles().stream().filter(t -> t.getUserData() == m && !((RadioButton) t).isDisabled()).findFirst().ifPresent(t -> t.setSelected(true));
    }

    @Override public void stop() { active = false; }

    void readNow() {
        if (parts.watcher != null) parts.watcher.pollNow(); else note.setText("Turn the account watcher on to read the accounts.");
    }

    /** A block of an account gets its switch button, and a mark while it is the account Claude Code uses. */
    void decorate(UsageBlock block, ccsuite.usagetracker.model.AccountUsage page) {
        if ("ALL".equals(block.id)) return;
        var here = UsageLine.text("● Claude Code is on it", "-fx-text-fill: #1e88e5; -fx-font-weight: bold;");
        var go = new Button("Switch to " + block.id);
        go.setOnAction(e -> SwitchConfirm.ask(parts, block.id));
        block.actions.getChildren().setAll(here, go);
    }

    /** Which controls of each block show: the mark on the active account, the button on every other. */
    void marks(List<ccsuite.usagetracker.model.AccountUsage> pages) {
        for (var p : pages) {
            var b = view.blocks.get(p.credential().id());
            if (b == null || b.actions.getChildren().size() < 2) continue;
            var on = p.credential().id().equals(bridge.activeId);
            b.actions.getChildren().get(0).setVisible(on); b.actions.getChildren().get(0).setManaged(on);
            b.actions.getChildren().get(1).setVisible(!on); b.actions.getChildren().get(1).setManaged(!on);
            b.actions.getChildren().get(1).setDisable(parts.watcher == null);
        }
    }

    Map<String, LastReadings.Reading> logged = Map.of();
    long loggedAt;

    /** The pages, with the log's last reading for an account the watcher has none for (read at most every five minutes, and only when one is missing). */
    List<ccsuite.usagetracker.model.AccountUsage> pages() {
        var creds = parts.core == null ? List.<ccsuite.base.model.CCAPICredential>of() : parts.core.creds.credentials();
        var pages = PaidPages.of(bridge.usages, creds);
        var have = pages.stream().map(p -> p.credential().id()).collect(java.util.stream.Collectors.toSet());
        if (creds.stream().noneMatch(c -> ccsuite.base.model.CCAPIsCredentials.paid(c) && c.trackUsage() && !have.contains(c.id()))) return pages;
        if (System.currentTimeMillis() - loggedAt > 5 * 60_000L) { logged = LastReadings.load(LastReadings.standard(), 3); loggedAt = System.currentTimeMillis(); }
        return PaidPages.of(bridge.usages, creds, logged, Instant.now());
    }

    void redraw() {
        if (!active) return;
        redrawing = true;
        try {
            var w = parts.watcher;
            var pages = pages();
            view.show(pages, PaidPages.all(pages), Map.of(), pages.isEmpty() ? "No reading yet: the account watcher has not polled." : "ALL needs two accounts with a reading.");
            marks(pages);
            var target = bridge.pendingTarget;
            var show = target != null && w != null;
            recommend.setVisible(show);
            recommend.setManaged(show);
            if (show) { recommendText.setText("Recommended: move Claude Code to " + target + "."); recommendButton.setText("Switch to " + target); }
            modes.getToggles().forEach(t -> { t.setSelected(w != null && t.getUserData() == w.mode()); ((RadioButton) t).setDisable(w == null); });
            switching.setText("Switching: " + switching(w == null ? null : w.mode()));
        } finally {
            redrawing = false;
        }
    }

    static String switching(WatchMode m) {
        if (m == null) return "off (the account watcher is not running)";
        return m == WatchMode.AUTO ? "auto: ccacc moves Claude Code between accounts on its own" : "off: Claude Code stays on one account";
    }

    static String modeText(WatchMode m) {
        return switch (m) {
            case AUTO -> "Auto  (F6)";
            case LOCKED -> "Locked: stay, still warn  (F7)";
            case PAUSED -> "Paused: no polling  (F8)";
        };
    }
}
