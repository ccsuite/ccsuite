package ccsuite.ccacc;

import module java.base;
import ccsuite.ccgate.Clients;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/**
 * The Clients tab: who calls the gate (locally and through the relay), how hard, and the means to rein them in: kick,
 * suspend, rate limit (refuse or queue the excess) and throttle (slower answers). Refreshes every two seconds while
 * the window is open and not at all when it is closed. Keyboard first: Up/Down choose, Del kicks, S suspends, U lifts,
 * R sets the rate, T throttles.
 */
final class ClientsPane extends VBox {

    static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    static final Map<String, Duration> SUSPEND = new LinkedHashMap<>();
    static final Map<String, int[]> RATES = new LinkedHashMap<>();
    static final Map<String, Integer> SPEEDS = new LinkedHashMap<>();

    static {
        SUSPEND.put("15 minutes", Duration.ofMinutes(15));
        SUSPEND.put("1 hour", Duration.ofHours(1));
        SUSPEND.put("6 hours", Duration.ofHours(6));
        SUSPEND.put("24 hours", Duration.ofHours(24));
        SUSPEND.put("7 days", Duration.ofDays(7));
        RATES.put("No limit", new int[] {0, 0});
        for (var n : new int[] {5, 10, 20, 30, 60}) {
            RATES.put(n + " calls a minute, the excess is refused", new int[] {n, 0});
            RATES.put(n + " calls a minute, the excess waits its turn (up to 30 s)", new int[] {n, 1});
        }
        SPEEDS.put("Full speed", 0);
        SPEEDS.put("10 KB/s (barely noticeable)", 10_000);
        SPEEDS.put("3 KB/s (slow)", 3_000);
        SPEEDS.put("1 KB/s (very slow)", 1_000);
        SPEEDS.put("300 B/s (a crawl)", 300);
    }

    final Parts parts;
    final TableView<Clients.Row> table = new TableView<>();
    final Label note = new Label();
    ScheduledExecutorService ticker;

    ClientsPane(Parts parts) {
        super(8);
        this.parts = parts;
        setPadding(new Insets(12, 14, 10, 14));

        table.setPlaceholder(new Label("No callers yet. They appear here once a grant exists."));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.getColumns().addAll(java.util.List.of(
                col("Client", 120, r -> r.id()),
                col("Status", 78, r -> r.status()),
                col("From", 52, r -> r.viaRelay() ? "relay" : "local"),
                col("/min", 44, r -> String.valueOf(r.lastMinute())),
                col("Calls", 52, r -> String.valueOf(r.calls())),
                col("Refused", 60, r -> String.valueOf(r.refused())),
                col("Busy", 44, r -> String.valueOf(r.inFlight())),
                col("Points today", 86, r -> String.format(Locale.ROOT, "%.2f", r.pointsToday())),
                col("Last seen", 76, r -> r.lastSeen() == null ? "never" : TIME.format(r.lastSeen())),
                col("Limits", 150, r -> r.limits())));
        table.setOnKeyPressed(e -> {
            switch (e.getCode()) {
                case DELETE -> kick();
                case S -> suspend();
                case U -> lift();
                case R -> rate();
                case T -> throttle();
                default -> { return; }
            }
            e.consume();
        });
        VBox.setVgrow(table, javafx.scene.layout.Priority.ALWAYS);

        var kick = button("Kick  (Del)", this::kick);
        var suspend = button("Suspend…  (S)", this::suspend);
        var lift = button("Lift  (U)", this::lift);
        var rate = button("Rate…  (R)", this::rate);
        var throttle = button("Throttle…  (T)", this::throttle);
        note.setWrapText(true);
        note.setStyle("-fx-text-fill: #8a3b1c;");
        var legend = new Label("Kick revokes the grant and its keys. Suspend refuses it for a while. Rate limits calls a minute (queue or refuse the excess). Throttle slows the answers. Issuing a new grant or key asks you first.");
        legend.setWrapText(true);
        legend.setStyle("-fx-font-size: 10; -fx-text-fill: #888;");
        getChildren().addAll(table, new HBox(8, kick, suspend, lift, rate, throttle), note, legend);
    }

    static Button button(String text, Runnable action) {
        var b = new Button(text);
        b.setOnAction(e -> action.run());
        return b;
    }

    static TableColumn<Clients.Row, String> col(String title, double width, java.util.function.Function<Clients.Row, String> value) {
        var c = new TableColumn<Clients.Row, String>(title);
        c.setPrefWidth(width);
        c.setSortable(false);
        c.setCellValueFactory(cell -> new SimpleStringProperty(value.apply(cell.getValue())));
        return c;
    }

    // ---- refreshing, only while the window is open

    void start() {
        if (ticker != null) return;
        refresh();
        ticker = Executors.newSingleThreadScheduledExecutor(r -> Thread.ofPlatform().daemon().name("ccacc-clients").unstarted(r));
        ticker.scheduleWithFixedDelay(() -> Platform.runLater(this::refresh), 2, 2, TimeUnit.SECONDS);
    }

    void stop() {
        if (ticker != null) ticker.shutdownNow();
        ticker = null;
    }

    void refresh() {
        var clients = parts.gate.clients();
        if (clients == null) {
            table.getItems().clear();
            note.setText("The gate is off. Turn it on in the tray menu to see and rein in its callers.");
            return;
        }
        var chosen = selectedId();
        var rows = clients.rows();
        table.getItems().setAll(rows);
        for (var i = 0; i < rows.size(); i++)
            if (rows.get(i).id().equals(chosen)) { table.getSelectionModel().select(i); break; }
    }

    String selectedId() {
        var r = table.getSelectionModel().getSelectedItem();
        return r == null ? null : r.id();
    }

    // ---- the controls

    interface Action {
        void run(Clients clients, String id) throws IOException;
    }

    void act(String done, Action action) {
        var id = selectedId();
        var clients = parts.gate.clients();
        if (clients == null) { note.setText("The gate is off."); return; }
        if (id == null) { note.setText("Choose a client first."); return; }
        try {
            action.run(clients, id);
            note.setText(done.formatted(id));
        } catch (IOException | RuntimeException e) {
            note.setText("Could not do that: " + e.getMessage());
        }
        refresh();
    }

    void kick() {
        var id = selectedId();
        if (id == null) { note.setText("Choose a client first."); return; }
        var ask = new Alert(Alert.AlertType.CONFIRMATION, "Revoke '" + id + "' and every key it holds? It will be refused from the next call on.", ButtonType.OK, ButtonType.CANCEL);
        ask.setHeaderText("Kick " + id + "?");
        ask.showAndWait().filter(b -> b == ButtonType.OK).ifPresent(b -> act("Kicked %s: revoked, with its keys.", (c, g) -> c.kick(g)));
    }

    void suspend() {
        choose("Suspend", "Suspend for how long?", new ArrayList<>(SUSPEND.keySet())).ifPresent(s ->
                act("Suspended %s for " + s + ".", (c, g) -> c.suspend(g, SUSPEND.get(s), "suspended by the owner")));
    }

    void lift() {
        act("%s is no longer suspended.", (c, g) -> c.lift(g));
    }

    void rate() {
        choose("Rate limit", "Calls a minute for this client:", new ArrayList<>(RATES.keySet())).ifPresent(s ->
                act("Rate of %s set: " + s + ".", (c, g) -> c.rate(g, RATES.get(s)[0], RATES.get(s)[1] == 1)));
    }

    void throttle() {
        choose("Throttle", "Speed of the answers to this client:", new ArrayList<>(SPEEDS.keySet())).ifPresent(s ->
                act("Answers to %s: " + s + ".", (c, g) -> c.throttle(g, SPEEDS.get(s))));
    }

    Optional<String> choose(String title, String header, java.util.List<String> items) {
        if (selectedId() == null) { note.setText("Choose a client first."); return Optional.empty(); }
        var d = new ChoiceDialog<>(items.get(0), items);
        d.setTitle(title);
        d.setHeaderText(header);
        return d.showAndWait();
    }
}
