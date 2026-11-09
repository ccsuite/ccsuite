package ccsuite.ccacc;

import module java.base;
import ccsuite.base.model.CCAPIsCredentials;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;

/**
 * The Settings sub-tab of the Lanes tab: the settings that were only {@code ccacc lanes set …} commands, as a form (limits as percent). Apply (F2) sends only what was
 * changed, one command each, through {@link LaneCommands}: the same checks and the same live effect as the command line, and a refused value says why while the rest
 * still applies. The ports, the slots per account and the named free accounts take effect when the lanes next start.
 */
final class LaneSettingsPane extends VBox {

    final Parts parts;
    final TextField five = new TextField(), week = new TextField(), slots = new TextField(), every = new TextField(), fresh = new TextField(), spread = new TextField(),
            pclaudePort = new TextField(), fclaudePort = new TextField(), accounts = new TextField();
    final ChoiceBox<String> chatReuse = new ChoiceBox<>();
    final ComboBox<String> paidPin = new ComboBox<>();
    final Label note = new Label();

    LaneSettingsPane(Parts parts) {
        super(10);
        this.parts = parts;
        setPadding(new Insets(10, 0, 0, 0));
        chatReuse.getItems().addAll("on", "off", "fresh");
        var grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(7);
        var row = 0;
        row = add(grid, row, "Free five-hour limit (% of the window a free account is used up to)", five);
        row = add(grid, row, "Free weekly limit (%)", week);
        row = add(grid, row, "Calls at once per free account (applies at the next start)", slots);
        row = add(grid, row, "Ping a free account every (minutes)", every);
        row = add(grid, row, "A reading is fresh for (minutes)", fresh);
        row = add(grid, row, "Spread the pings over (seconds)", spread);
        row = add(grid, row, "pclaude port, the paid lane (applies at the next start)", pclaudePort);
        row = add(grid, row, "fclaude port, the free lane (applies at the next start)", fclaudePort);
        row = add(grid, row, "Free accounts the lane uses (all, or ids like F1,F2; next start)", accounts);
        row = add(grid, row, "Reuse chats (on, off, fresh)", chatReuse);
        add(grid, row, "Paid lane uses only this account (off: any)", paidPin);
        var apply = new Button("Apply  (F2)");
        apply.setOnAction(e -> apply());
        note.setWrapText(true);
        note.setStyle("-fx-text-fill: #8a3b1c;");
        getChildren().addAll(grid, apply, note);
    }

    static int add(GridPane g, int row, String label, javafx.scene.Node field) {
        g.addRow(row, new Label(label), field);
        return row + 1;
    }

    LaneSettings lanes() { return parts.lanes != null ? parts.lanes.lanes() : parts.store.load().lanes(); }

    static String percent(double fraction) { return java.math.BigDecimal.valueOf(fraction).movePointRight(2).stripTrailingZeros().toPlainString(); }

    static String fraction(String percent) { return new java.math.BigDecimal(percent.trim()).movePointLeft(2).toPlainString(); }

    /** The form from the settings as they are now. */
    void fill() {
        var l = lanes();
        five.setText(percent(l.freeFiveLimit()));
        week.setText(percent(l.freeWeekLimit()));
        slots.setText(String.valueOf(l.perAccountSlots()));
        every.setText(String.valueOf(l.freePingEveryMinutes()));
        fresh.setText(String.valueOf(l.freePingFreshMinutes()));
        spread.setText(String.valueOf(l.freePingSpreadSeconds()));
        pclaudePort.setText(String.valueOf(l.pclaudePort()));
        fclaudePort.setText(String.valueOf(l.fclaudePort()));
        accounts.setText(l.freeAccounts().isEmpty() ? "all" : String.join(",", l.freeAccounts()));
        chatReuse.setValue(l.chatReuseMode());
        var ids = new ArrayList<String>(List.of("off"));
        if (parts.core != null) parts.core.creds.credentials().stream().filter(CCAPIsCredentials::paid).map(c -> c.id()).forEach(ids::add);
        var pin = l.paidPinOrNull() == null ? "off" : l.paidPinOrNull();
        if (!ids.contains(pin)) ids.add(pin);
        paidPin.getItems().setAll(ids);
        paidPin.setValue(pin);
    }

    /** Sends each changed field as a command and shows what happened. */
    void apply() {
        var l = lanes();
        var run = new LaneCommands(parts.store, parts.lanes);
        var said = new ArrayList<String>();
        var failed = new ArrayList<String>();
        var fields = new LinkedHashMap<String, String[]>();   // key -> {form text, text as it is now, value to send}
        try {
            fields.put("freeFiveLimit", new String[]{five.getText().trim(), percent(l.freeFiveLimit()), fraction(five.getText())});
            fields.put("freeWeekLimit", new String[]{week.getText().trim(), percent(l.freeWeekLimit()), fraction(week.getText())});
        } catch (NumberFormatException e) {
            failed.add("a limit is a number, as percent (for example 97)");
        }
        fields.put("perAccountSlots", same(slots, String.valueOf(l.perAccountSlots())));
        fields.put("freePingEveryMinutes", same(every, String.valueOf(l.freePingEveryMinutes())));
        fields.put("freePingFreshMinutes", same(fresh, String.valueOf(l.freePingFreshMinutes())));
        fields.put("freePingSpreadSeconds", same(spread, String.valueOf(l.freePingSpreadSeconds())));
        fields.put("pclaudePort", same(pclaudePort, String.valueOf(l.pclaudePort())));
        fields.put("fclaudePort", same(fclaudePort, String.valueOf(l.fclaudePort())));
        fields.put("chatReuse", new String[]{String.valueOf(chatReuse.getValue()), l.chatReuseMode(), String.valueOf(chatReuse.getValue())});
        fields.put("paidPin", new String[]{String.valueOf(paidPin.getValue()), l.paidPinOrNull() == null ? "off" : l.paidPinOrNull(), String.valueOf(paidPin.getValue())});
        fields.forEach((key, v) -> {
            if (v[0].equals(v[1])) return;
            var r = run.run(List.of("lanes", "set", key, v[2]));
            (r.ok() ? said : failed).add(r.message());
        });
        var named = accounts.getText().trim();
        var current = l.freeAccounts().isEmpty() ? "all" : String.join(",", l.freeAccounts());
        if (!named.replace(" ", "").equals(current)) {
            var r = run.run(List.of("lanes", "free-accounts", named));
            (r.ok() ? said : failed).add(r.message());
        }
        note.setText((failed.isEmpty() ? "" : "Refused: " + String.join("; ", failed) + "\n") + (said.isEmpty() ? (failed.isEmpty() ? "Nothing was changed." : "") : "Saved: " + String.join("; ", said)));
        fill();
    }

    static String[] same(TextField f, String current) { return new String[]{f.getText().trim(), current, f.getText().trim()}; }
}
