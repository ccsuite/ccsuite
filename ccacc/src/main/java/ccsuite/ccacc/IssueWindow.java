package ccsuite.ccacc;

import module java.base;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import leasegate.frame.Arming;
import leasegate.frame.Brand;
import leasegate.frame.GateFrame;
import leasegate.frame.GateIcons;
import leasegate.frame.Parts;

/**
 * The window that asks the owner before a grant or a key is issued from the command line, in the leasegate standard:
 * the app's colour band first, keys ignored until the window has settled, F2 approves and Esc denies. It says in plain
 * words what would be issued; nothing is issued until F2. Restricting a caller needs no such window.
 */
final class IssueWindow {

    static final Duration WAIT = Duration.ofSeconds(100);
    static final Brand BRAND = new Brand("ccacc gate", CcaccApp.COLOR, new GateIcons(CcaccApp.class, "ccacc"));

    final String what;
    final Consumer<Boolean> onDecision;
    final GateFrame frame;
    final Stage stage;
    Arming arming;
    boolean decided;

    IssueWindow(String what, Consumer<Boolean> onDecision) {
        this.what = what;
        this.onDecision = onDecision;
        this.frame = new GateFrame(BRAND, "issuing request: a command asks to give access to your Claude accounts").onClose(() -> decide(false));
        this.stage = frame.stage;
    }

    /** Shows the window and waits for the owner; false when denied, closed, or nobody answered in time. Called off the JavaFX thread. */
    static boolean ask(String what) {
        var answer = new CompletableFuture<Boolean>();
        var window = new AtomicReference<IssueWindow>();
        Platform.runLater(() -> {
            var w = new IssueWindow(what, answer::complete);
            window.set(w);
            w.show();
        });
        try {
            return answer.get(WAIT.toSeconds(), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            Platform.runLater(() -> { var w = window.get(); if (w != null) w.decide(false); });
            return false;
        } catch (InterruptedException | ExecutionException e) {
            return false;
        }
    }

    void show() {
        var approve = Parts.button("F2  Approve", Parts.GREEN, this::approve);
        var deny = Parts.button("Esc  Deny", Parts.RED, () -> decide(false));
        var armingLine = Parts.line(" ", Parts.AMBER);
        arming = new Arming(List.of(approve, deny), armingLine);

        var text = new Label(what);
        text.setWrapText(true);
        text.setStyle("-fx-font-size: 13; -fx-text-fill: #222;");
        var root = new VBox(12,
                Parts.line("A command on this machine asks to issue access.", "#333"),
                text,
                Parts.line("Nothing is issued until you approve. Expires on its own if you do not answer.", "#555"),
                new HBox(8, approve, deny), armingLine,
                Parts.small("F2 approve · Esc deny", "#777"));
        root.setPadding(new Insets(14));
        root.setPrefWidth(620);

        var scene = frame.scene(root);
        arming.guard(scene);
        scene.addEventFilter(KeyEvent.KEY_PRESSED, this::onKey);
        stage.setTitle("CCACC gate: approve issuing?");
        stage.show();
        stage.toFront();
    }

    void onKey(KeyEvent e) {
        if (!arming.armed) { e.consume(); return; }
        switch (e.getCode()) {
            case F2 -> approve();
            case ESCAPE -> decide(false);
            default -> { return; }
        }
        e.consume();
    }

    void approve() {
        if (arming.armed) decide(true);
    }

    void decide(boolean yes) {
        if (decided) return;
        decided = true;
        onDecision.accept(yes);
        stage.close();
    }
}
