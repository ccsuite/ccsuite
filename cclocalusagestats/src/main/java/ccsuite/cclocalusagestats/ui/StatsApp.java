package ccsuite.cclocalusagestats.ui;

import static luvjfx.Fx.*;

import ccsuite.cclocalusagestats.cli.Roots;
import ccsuite.cclocalusagestats.db.*;
import ccsuite.cclocalusagestats.ingest.*;
import ccsuite.cclocalusagestats.report.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import javafx.application.*;
import javafx.beans.property.*;
import javafx.scene.Scene;
import javafx.scene.control.TableView;
import javafx.scene.input.*;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;

public final class StatsApp extends Application {

    static final String ORANGE = "#d97757";
    static final String KEYS = "F1-F9 views   S scan   P period   E export backup   Del delete selected session or project   X delete older or all   Esc quit";
    static final String[] PERIODS = {"last 7 days", "last 30 days", "all time"};

    final ExecutorService worker = Executors.newSingleThreadExecutor(r -> { var t = new Thread(r, "ledger"); t.setDaemon(true); return t; });
    final Filters filters = new Filters().top(0);
    final TableView<Map<String, Object>> table = new TableView<>();
    final StringProperty tabsText = new SimpleStringProperty(), statusText = new SimpleStringProperty("opening the ledger...");
    Ledger ledger;
    View view = View.DAILY;
    int period = 1;

    @Override
    public void start(Stage stage) {
        applyPeriod();
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY);
        view = View.values()[Integer.getInteger("stats.view", 1) - 1];
        var header = hbox().spacing(16).padding(10).style("-fx-background-color: " + ORANGE + ";").nodes(
            label("cclocalusagestats").style("-fx-text-fill: white; -fx-font-size: 16; -fx-font-weight: bold;"),
            label("").text(tabsText).style("-fx-text-fill: white; -fx-font-size: 13;"));
        var bar = hbox().spacing(16).padding(6).nodes(label("").text(statusText), label(KEYS).style("-fx-text-fill: #666;"));
        var scene = new Scene(new BorderPane(table, header.node, null, bar.node, null), 1100, 650);
        scene.setOnKeyPressed(this::key);
        stage.setTitle("cclocalusagestats");
        stage.getIcons().add(Icon.load());
        stage.setScene(scene);
        stage.show();
        worker.submit(() -> { ledger = Ledger.open(); refresh(); });
        var shot = System.getProperty("stats.screenshot");
        if (shot != null) Screenshot.after(scene, 4000, shot);
    }

    @Override
    public void stop() throws Exception {
        worker.submit(() -> { if (ledger != null) ledger.close(); });
        worker.shutdown();
        worker.awaitTermination(10, TimeUnit.SECONDS);
    }

    void key(KeyEvent e) {
        var c = e.getCode();
        var n = c.ordinal() - KeyCode.F1.ordinal();
        if (n >= 0 && n < View.values().length) { view = View.values()[n]; refresh(); return; }
        switch (c) {
            case S -> scan();
            case P -> { period = (period + 1) % PERIODS.length; applyPeriod(); refresh(); }
            case DELETE -> deleteSelected();
            case E -> exportNow();
            case X -> deleteOlder();
            case ESCAPE -> Platform.exit();
            default -> { }
        }
    }

    void applyPeriod() {
        filters.since(period == 2 ? null : LocalDate.now(filters.zone()).minusDays(period == 0 ? 7 : 30));
    }

    void say(String s) {
        Platform.runLater(() -> statusText.set(s));
    }

    void refresh() {
        var v = view;
        var tabs = new StringBuilder();
        for (var x : View.values()) tabs.append(x == v ? "[F" : " F").append(x.ordinal() + 1).append(' ').append(x.title).append(x == v ? "]  " : "   ");
        Platform.runLater(() -> tabsText.set(tabs.toString()));
        worker.submit(() -> {
            if (ledger == null) return;
            var rows = v.load.apply(ledger.db, filters);
            Platform.runLater(() -> { ResultTable.fill(table, rows); table.requestFocus(); });
            say(rows.size() + " rows, " + PERIODS[period]);
        });
    }

    void scan() {
        say("scanning...");
        worker.submit(() -> {
            try {
                SwitchLogScanner.scan(ledger, SwitchLogScanner.LOGS);
                ReadingScanner.scan(ledger, ReadingScanner.DIR);
                var stats = new TranscriptScanner(ledger).scan(Roots.resolve(null));
                say("scan done: " + stats);
            } catch (Exception ex) {
                say("scan failed: " + ex);
            }
            refresh();
        });
    }

    void exportNow() {
        say("exporting...");
        worker.submit(() -> {
            try {
                var counts = Backup.export(ledger.db, Backup.DEFAULT_DIR);
                say("exported " + counts.values().stream().mapToLong(Long::longValue).sum() + " rows to " + Backup.DEFAULT_DIR);
            } catch (Exception ex) {
                say("export failed: " + ex);
            }
        });
    }

    void deleteSelected() {
        var row = table.getSelectionModel().getSelectedItem();
        if (row == null || (view != View.SESSIONS && view != View.PROJECTS)) { say("select a row in Sessions (F3) or Projects (F2) to delete it"); return; }
        var key = String.valueOf(row.get("key"));
        worker.submit(() -> confirmAndRun(view == View.SESSIONS ? DeletePlan.session(Dimension.id8(key)) : DeletePlan.project(ledger.db, key), key));
    }

    void deleteOlder() {
        Platform.runLater(() -> Dialogs.ask("Delete everything before which day?  yyyy-MM-dd, or ALL for everything").ifPresent(s -> {
            try {
                var plan = s.equalsIgnoreCase("ALL") ? DeletePlan.everything() : DeletePlan.before(LocalDate.parse(s), filters.zone());
                worker.submit(() -> confirmAndRun(plan, s));
            } catch (RuntimeException ex) {
                say("not a day: " + s);
            }
        }));
    }

    void confirmAndRun(DeletePlan plan, String what) {
        var counts = plan.counts(ledger.db);
        var total = counts.values().stream().mapToLong(Long::longValue).sum();
        if (total == 0) { say("nothing to delete for " + what); return; }
        var text = what + "\n\n" + counts + "\n\nThe transcripts are not touched. This cannot be undone.";
        Platform.runLater(() -> {
            if (Dialogs.confirm(text)) worker.submit(() -> { plan.run(ledger.db); say("deleted " + total + " rows"); refresh(); });
        });
    }
}
