package ccsuite.ccacc;

import module java.base;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.*;
import javafx.util.Duration;

/**
 * The free accounts, and only they, as a tab of the main window: ALL first, then each account as graphs ({@link UsageView}), with its state in words. It is built
 * with the main window and draws from the gauge table already held: opening it never reads an account. F5 (or the button) reads every account again now.
 * The numbers come from {@link FreeGauges}.
 */
final class FreePane extends VBox implements Page {

    final FreeGauges gauges;
    final Supplier<LaneSettings> limits;
    final UsageView view = new UsageView("#26a8b8");
    final Label summary = new Label(), room = new Label();
    final Timeline tick = new Timeline(new KeyFrame(Duration.seconds(20), e -> redraw()));
    boolean active;
    /** Shows or hides the floating free usage window; set by the main window. */
    Runnable onDialog = () -> {};

    FreePane(FreeGauges gauges, Supplier<LaneSettings> limits) {
        super(10);
        this.gauges = gauges;
        this.limits = limits;
        summary.setWrapText(true);
        summary.setMinHeight(Region.USE_PREF_SIZE);
        room.setMinHeight(Region.USE_PREF_SIZE);
        summary.setStyle("-fx-text-fill: #555;");
        var dialog = new Button("Floating window  (F9)");
        dialog.setOnAction(e -> onDialog.run());
        var read = new Button("Read the free accounts now  (F5)");
        read.setOnAction(e -> refresh());
        setPadding(new Insets(12, 14, 10, 14));
        VBox.setVgrow(view, Priority.ALWAYS);
        room.setStyle("-fx-font-weight: bold;");
        getChildren().addAll(room, view, new HBox(10, read, dialog), summary);
        tick.setCycleCount(Timeline.INDEFINITE);
    }

    /** Every free account, in the order and with the state text of the widget and the commands ({@link FreeStates}); an account with no reading at all is listed too. */
    static List<FreeStates.Entry> entries(FreeGauges gauges, Instant now, ZoneId zone) {
        return FreeStates.entries(gauges.accounts.get(), gauges.table(), List.of(), now, zone);
    }

    /** A reading taken from a probe, a ping or a served call in this run; one read back from the ledger at start is the past, not live. */
    static boolean live(FreeGauges gauges, FreeStates.Entry e) {
        return e.row() != null && e.row().ok() && !"ledger".equals(gauges.sources.get(e.id()));
    }

    /** Each account's state in words, and the server's own words when its probe failed. */
    static Map<String, String> states(List<FreeStates.Entry> entries) {
        var m = new HashMap<String, String>();
        entries.forEach(e -> m.put(e.id(), e.row() != null && e.row().problem() != null && e.row().five() == null ? e.describe() + " (! " + e.row().problem() + ")" : e.describe()));
        return m;
    }

    /** The text under the graphs. The percentage counts live readings only, and the text says how many accounts that is, so it is not read as a count of the list above it. */
    static String summaryText(FreeGauges gauges, List<FreeStates.Entry> entries) {
        if (entries.isEmpty()) return "No free accounts saved: add one in Manage accounts… (tier 0 with a web session key and an organisation id).";
        var liveRows = entries.stream().filter(e -> live(gauges, e)).map(FreeStates.Entry::row).toList();
        var left = liveRows.stream().mapToDouble(r -> 1 - r.five()).sum();
        var total = liveRows.isEmpty() ? "No live reading yet, so no total."
            : "About " + Math.round(left * 100) + "% of one five-hour window is left across the " + liveRows.size() + " accounts with a live reading (readings from the ledger and accounts not read yet are listed above but not counted).";
        return entries.size() + " free accounts. " + total + " Free accounts have no usage endpoint and no weekly reset: each reading is a one-word probe, kept in ~/ccsuite/ccacc/free-readings/. F5 reads again.";
    }

    /** The tab is in front of a shown window: draw now and keep the times current. */
    @Override public void start() {
        active = true;
        redraw();
        tick.play();
        view.requestFocus();
    }

    @Override public List<KeyAction> keys() {
        return List.of(new KeyAction(KeyCode.F5, "Read now", this::refresh), new KeyAction(KeyCode.F9, "Floating window", () -> onDialog.run()));
    }

    @Override public void stop() {
        active = false;
        tick.stop();
    }

    void refresh() {
        summary.setText("Reading every free account now (one small message each)…");
        gauges.refreshInBackground();
    }

    void redrawLater() {
        Platform.runLater(this::redraw);
    }

    void redraw() {
        if (!active) return;
        var now = Instant.now();
        var entries = entries(gauges, now, ZoneId.systemDefault());
        var lanes = limits.get();
        var pages = FreeCard.accounts(entries, now);
        var all = pages.size() < 2 ? null : FreeCard.overall(gauges.snapshot(), lanes.freeFiveLimit(), lanes.freeWeekLimit(), now);
        view.show(pages, all, states(entries), entries.isEmpty() ? "No free accounts." : null);
        summary.setText(summaryText(gauges, entries));
        var o = FreeOverall.of(gauges.snapshot(), lanes.freeFiveLimit(), lanes.freeWeekLimit(), now);
        room.setText(o.accounts() == 0 ? "" : o.withRoom() + " of " + o.accounts() + " free accounts have room");
    }
}
