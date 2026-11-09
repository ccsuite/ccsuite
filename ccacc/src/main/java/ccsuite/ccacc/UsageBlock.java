package ccsuite.ccacc;

import ccsuite.base.usage.Cooldown;
import ccsuite.usagetracker.model.AccountUsage;
import java.time.Duration;
import java.time.OffsetDateTime;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/**
 * One account (or ALL) as graphs, the same for paid and free: a line for the five-hour window and one for the week ({@link UsageLine}), the name, its state in
 * words and a slot at the right for the actions of the view that shows it. On the week line also the cool-down: how long to leave the account unused for its
 * weekly ratio to fall under 100%. Built once per account and updated in place.
 */
final class UsageBlock extends VBox {

    static final long FIVE_HOURS = 5 * 3600L, WEEK = 7 * 24 * 3600L;

    final String id;
    final Label name = UsageLine.text("", "-fx-font-weight: bold; -fx-font-size: 13;"), state = UsageLine.text("", "-fx-text-fill: #666;");
    final HBox actions = new HBox(6);
    final UsageLine five, week;

    UsageBlock(String id, String timeColor) {
        super(3);
        this.id = id;
        five = new UsageLine("5h", FIVE_HOURS, "window has reset", timeColor);
        week = new UsageLine("week", WEEK, "no weekly reset known", timeColor);
        actions.setAlignment(Pos.CENTER_LEFT);
        getChildren().addAll(new HBox(10, name, state, actions), five, week);
        setStyle("-fx-padding: 6 0 6 0;");
    }

    /** {@code extra} is the account's state in words, shown beside its name. */
    void update(AccountUsage a, String extra) {
        var c = a.credential();
        var d = a.usageData();
        name.setText("ALL".equals(c.id()) ? "ALL  " + c.tier() + " accounts" : c.id());
        state.setText(withAge(a, extra));
        five.update(d == null ? null : d.fiveHour(), "");
        week.update(d == null ? null : d.sevenDay(), coolDown(a));
    }

    /** The state in words, and when the reading is older than three minutes how old it is: a value drawn from the cache says so. ALL and a state that names its age already add nothing. */
    static String withAge(AccountUsage a, String extra) {
        var state = extra == null ? "" : extra;
        if ("ALL".equals(a.credential().id()) || a.fetchedAt() == null || state.contains(" ago")) return state;
        var old = Duration.between(a.fetchedAt(), OffsetDateTime.now());
        if (old.compareTo(Duration.ofMinutes(3)) < 0) return state;
        return (state.isEmpty() ? "" : state + "  ") + "reading " + Cooldown.terse(old) + " old (cached)";
    }

    /** "cool-down 1d5h", "cool-down none" (already under 100%), or "cool-down -" when the week's reset is not known. */
    static String coolDown(AccountUsage a) {
        var d = a.usageData();
        var week = d == null ? null : d.sevenDay();
        if (week == null || week.resetsAt() == null) return "cool-down -";
        var left = a.getWeeklyCoolDownFormatted();
        return "cool-down " + (left == null ? "none" : left);
    }
}
