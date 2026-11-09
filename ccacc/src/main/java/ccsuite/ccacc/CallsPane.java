package ccsuite.ccacc;

import module java.base;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * The Calls report sub-tab of the Lanes tab: what the lanes served and what it cost, grouped by job, session, account or model, over all of the log or the last
 * half hour to a day, and what failed or ran slow. Read from the log files when asked (first time the sub-tab is shown, then Load); never on a timer.
 */
final class CallsPane extends VBox {

    static final Map<String, Duration> SINCE = new LinkedHashMap<>();

    static {
        SINCE.put("all of it", null);
        SINCE.put("last 30 minutes", Duration.ofMinutes(30));
        SINCE.put("last hour", Duration.ofHours(1));
        SINCE.put("last 6 hours", Duration.ofHours(6));
        SINCE.put("last 24 hours", Duration.ofHours(24));
    }

    final Parts parts;
    final ChoiceBox<String> by = new ChoiceBox<>(), since = new ChoiceBox<>();
    final TextField job = new TextField();
    final TextArea out = new TextArea();
    boolean loaded;

    CallsPane(Parts parts) {
        super(8);
        this.parts = parts;
        setPadding(new Insets(8, 0, 0, 0));
        by.getItems().addAll("job", "session", "account", "model");
        by.setValue("job");
        since.getItems().addAll(SINCE.keySet());
        since.setValue("last 24 hours");
        job.setPromptText("one job tag (empty: all)");
        var load = new Button("Load");
        load.setOnAction(e -> load());
        out.setEditable(false);
        out.setStyle("-fx-font-family: 'Consolas', 'Monospaced'; -fx-font-size: 12;");
        VBox.setVgrow(out, Priority.ALWAYS);
        getChildren().addAll(new HBox(10, new Label("Group by"), by, new Label("Over"), since, job, load), out);
    }

    /** First time the sub-tab is in front. */
    void loadOnce() {
        if (!loaded) load();
    }

    void load() {
        loaded = true;
        out.setText("Reading the call logs…");
        var group = by.getValue();
        var window = SINCE.get(since.getValue());
        var tag = job.getText().isBlank() ? null : job.getText().trim();
        var host = parts.lanes;
        Thread.ofPlatform().daemon().name("calls-report").start(() -> {
            String text;
            try {
                var open = host == null ? Optional.<List<String>>empty() : Optional.of(host.openLines().stream().filter(l -> l.startsWith("open ")).toList());
                text = String.join("\n", CallsView.lines(CallsCli.logs(), tag, window, group, Duration.ofSeconds(60), open));
            } catch (IOException | RuntimeException e) {
                text = "The call logs could not be read: " + e.getMessage();
            }
            var shown = text;
            Platform.runLater(() -> out.setText(shown));
        });
    }
}
