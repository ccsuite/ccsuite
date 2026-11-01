package ccsuite.ccacc;

import ccsuite.ccgate.Approval;
import ccsuite.ccgate.ApprovalRequest;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import leasegate.frame.Arming;
import leasegate.frame.Brand;
import leasegate.frame.GateFrame;
import leasegate.frame.Parts;

/**
 * The window that asks the owner about a caller the gate does not know, in the leasegate standard: the app's colour band
 * first, keys ignored until the window has settled, approving takes F2 and never a letter, Esc denies. Two choices, both
 * on arrow keys: how far up the models go (Up and Down) and for how long (Left and Right). Nothing is granted until F2.
 */
final class ApprovalWindow {

    static final List<String> TIERS = List.of("Haiku", "Haiku + Sonnet", "Haiku + Sonnet + Opus");
    static final List<String> LONG = List.of("1 day", "7 days", "30 days");
    static final List<Integer> DAYS = List.of(1, 7, 30);

    final ApprovalRequest request;
    final Brand brand;
    final Consumer<Optional<Approval>> onDecision;
    final GateFrame frame;
    final Stage stage;
    final Chips models = new Chips(TIERS), length = new Chips(LONG);
    final Label caps = Parts.small(" ", "#555");
    Arming arming;
    boolean decided;

    ApprovalWindow(ApprovalRequest request, Brand brand, Consumer<Optional<Approval>> onDecision) {
        this.request = request;
        this.brand = brand;
        this.onDecision = onDecision;
        this.frame = new GateFrame(brand, "approval request: a program asks to use your Claude accounts").onClose(() -> decide(Optional.empty()));
        this.stage = frame.stage;
        models.select(Approval.tierFor(request.family()));
        length.select(1);
    }

    void show() {
        var approve = Parts.button("F2  Approve", Parts.GREEN, this::approve);
        var deny = Parts.button("Esc  Deny", Parts.RED, () -> decide(Optional.empty()));
        var legend = Parts.small("↑/↓ models · ←/→ how long · F2 approve · Esc deny", "#777");
        var armingLine = Parts.line(" ", Parts.AMBER);
        arming = new Arming(List.of(approve, deny), armingLine);

        var root = new VBox(10,
                Parts.line("A program is calling the gate with a key it does not know.", "#333"),
                who(), title("Models it may use"), models.node(), title("For how long"), length.node(), caps,
                new HBox(8, approve, deny), armingLine, legend);
        root.setPadding(new Insets(14));
        root.setPrefWidth(600);
        refresh();

        var scene = frame.scene(root);
        arming.guard(scene);
        scene.addEventFilter(KeyEvent.KEY_PRESSED, this::onKey);
        stage.setTitle("CCACC gate: request from " + (request.agent() == null ? "an unnamed program" : request.agent()));
        stage.show();
        stage.toFront();
    }

    VBox who() {
        var box = new VBox(3);
        box.getChildren().add(Parts.line("Program:  " + (request.agent() == null ? "(sends no name)" : request.agent()), "#222"));
        box.getChildren().add(Parts.line("Key:  " + request.shortFingerprint() + "  (a fingerprint; the key itself is never shown)", "#222"));
        if (request.session() != null) box.getChildren().add(Parts.line("Claude Code session:  " + request.session(), "#222"));
        box.getChildren().add(Parts.line("Asked for:  " + (request.model() == null ? "no model (not a message call)" : request.model()), "#222"));
        return box;
    }

    static Label title(String text) {
        return Parts.line(text, "#555");
    }

    void refresh() {
        var a = Approval.of(models.index, 1);
        var text = new StringBuilder("Daily caps, in quota points (1 point = 1% of one account's 5-hour window): ");
        a.models().forEach(m -> text.append(m).append(' ').append(Math.round(a.daily().get(m))).append("  "));
        caps.setText(text.toString().stripTrailing() + ". Change them later with ccacc gate grant.");
    }

    void onKey(KeyEvent e) {
        if (!arming.armed) { e.consume(); return; }
        switch (e.getCode()) {
            case UP -> models.move(-1);
            case DOWN -> models.move(1);
            case LEFT -> length.move(-1);
            case RIGHT -> length.move(1);
            case F2 -> approve();
            case ESCAPE -> decide(Optional.empty());
            default -> { return; }
        }
        refresh();
        e.consume();
    }

    void approve() {
        if (!arming.armed) return;
        decide(Optional.of(Approval.of(models.index, DAYS.get(length.index))));
    }

    /** The wait ran out: close without a decision nobody will read. */
    void withdraw() {
        decide(Optional.empty());
    }

    void decide(Optional<Approval> d) {
        if (decided) return;
        decided = true;
        onDecision.accept(d);
        stage.close();
    }

    /** A row of choices, one selected, moved with the arrow keys. */
    static final class Chips {
        final List<Label> labels;
        final HBox box;
        int index;

        Chips(List<String> names) {
            labels = names.stream().map(Label::new).toList();
            box = new HBox(8, labels.toArray(new Label[0]));
        }

        HBox node() {
            return box;
        }

        void move(int by) {
            select(Math.max(0, Math.min(labels.size() - 1, index + by)));
        }

        void select(int i) {
            index = i;
            for (var n = 0; n < labels.size(); n++)
                labels.get(n).setStyle(n == i
                        ? "-fx-background-color: " + CcaccApp.COLOR + "; -fx-text-fill: white; -fx-font-weight: bold; -fx-padding: 4 12; -fx-background-radius: 4;"
                        : "-fx-background-color: #eee; -fx-text-fill: #444; -fx-padding: 4 12; -fx-background-radius: 4;");
        }
    }
}
