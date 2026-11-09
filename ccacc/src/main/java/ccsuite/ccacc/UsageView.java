package ccsuite.ccacc;

import module java.base;
import ccsuite.usagetracker.model.AccountUsage;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.layout.VBox;

/**
 * ALL first, then every account, each as the graphs of {@link UsageBlock}; the same view for the paid and for the free accounts. A refresh updates the blocks in place
 * and only builds nodes when an account appears or goes, so it stays quick with twenty accounts. {@code decorate} lets the owner put controls into a block.
 */
final class UsageView extends ScrollPane {

    final VBox rows = new VBox(2);
    final String timeColor;
    final Map<String, UsageBlock> blocks = new HashMap<>();
    final Label note = new Label();
    /** Puts the owner's controls into a block that has none yet (a switch button, say); null for none. */
    BiConsumer<UsageBlock, AccountUsage> decorate;
    List<String> shown = List.of();
    int count;

    UsageView(String timeColor) {
        this.timeColor = timeColor;
        rows.setPadding(new Insets(4, 6, 4, 6));
        setContent(rows);
        setFitToWidth(true);
        setHbarPolicy(ScrollBarPolicy.AS_NEEDED);
        setStyle("-fx-background-color: transparent;");
    }

    /** {@code all} may be null (fewer than two accounts, or nothing to average); {@code extras} maps an account id to its state in words; {@code noAll} says why there is no ALL. */
    void show(List<AccountUsage> pages, AccountUsage all, Map<String, String> extras, String noAll) {
        var ids = new ArrayList<String>();
        if (all != null) ids.add("ALL");
        pages.forEach(p -> ids.add(p.credential().id()));
        var note = all == null ? noAll : null;
        if (!ids.equals(shown) || !Objects.equals(note, this.note.getText().isEmpty() ? null : this.note.getText())) rebuild(ids, note);
        if (all != null) blocks.get("ALL").update(all, "");
        for (var p : pages) {
            var b = blocks.get(p.credential().id());
            b.update(p, extras.get(p.credential().id()));
            if (decorate != null && b.actions.getChildren().isEmpty()) decorate.accept(b, p);
        }
        count = ids.size();
    }

    void rebuild(List<String> ids, String noAll) {
        blocks.keySet().retainAll(ids);
        var nodes = new ArrayList<Node>();
        this.note.setText(noAll == null ? "" : noAll);
        if (noAll != null) nodes.add(this.note);
        for (var id : ids) {
            nodes.add(blocks.computeIfAbsent(id, i -> new UsageBlock(i, timeColor)));
            nodes.add(new Separator());
        }
        if (!nodes.isEmpty() && nodes.getLast() instanceof Separator) nodes.removeLast();
        rows.getChildren().setAll(nodes);
        shown = List.copyOf(ids);
    }
}
