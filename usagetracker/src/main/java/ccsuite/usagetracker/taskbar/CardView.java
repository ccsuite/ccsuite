package ccsuite.usagetracker.taskbar;

import module java.base;
import javafx.geometry.VPos;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.effect.DropShadow;
import javafx.scene.paint.Color;
import javafx.scene.text.*;
import ccsuite.base.usage.Consolidated;
import ccsuite.usagetracker.model.AccountUsage;
import ccsuite.usagetracker.ui.GlowPulse;

/** One account as a card: the original ccu row (5h usage, 5h time, weekly usage, weekly time), same font, colours and labels. */
final class CardView {
    static final double BTN = 23, LABEL = 17, RIGHT = 31, ROW = 8, GAP = 2, BAR = 5;
    static final long HOME_AFTER_MS = 10_000;
    static final Font BOLD = Font.font("System", FontWeight.BOLD, 8), SMALL = Font.font("System", FontWeight.BOLD, 7);
    static final Color
        RED = Color.web("#FF8A80"), ORANGE = Color.web("#FFAA00"), GREEN = Color.web("#44FF44"),
        BG = Color.web("#2A2A2A"), BG_OFF = Color.web("#3A2A2A"), GRAY = Color.web("#A0A0A0"), CURRENT = Color.web("#4FC3F7"),
        BTN_BG = Color.web("#363636"), BTN_HOT = Color.web("#4E4E4E"), SKY = Color.web("#87CEEB"), BLUE = Color.DODGERBLUE;

    List<AccountUsage> list = List.of();
    int cur;
    long lastClick;
    boolean hot;
    /** The name of the account Claude Code is on beats in fire colours with a glow; off when the settings say so. */
    boolean glow = true;
    final DropShadow neon = new DropShadow();

    /** Says "auto" or "off" for the line at the bottom of the card; null for a program with nothing to say (ccu alone). */
    Supplier<String> modeLabel = () -> null;
    /** All accounts as one more page after the last (null with fewer than two accounts), and the averages behind it. */
    AccountUsage all;
    Consolidated total = new Consolidated();

    CardView() { neon.setSpread(0.45); }

    /** The pages: every account, then ALL. */
    int count() { return list.size() + (all == null ? 0 : 1); }

    /** The free accounts' card: ALL is the FIRST page (the first screen is the overall), no account is "the active one", and the footer is the room line. */
    boolean freeStyle;

    AccountUsage current() {
        if (freeStyle && all != null) return cur == 0 ? all : list.get(cur - 1);
        return cur < list.size() ? list.get(cur) : all;
    }

    /** True when the account on show is the one Claude Code is on, i.e. when the name is beating. */
    boolean currentIsActive() { return !freeStyle && cur < list.size() && list.get(cur).credential().claudecodeactive(); }

    /** The free accounts in the order given, and their overall (built by the caller from what a free account reports) as the first page. */
    void setFree(List<AccountUsage> l, AccountUsage overall) {
        freeStyle = true;
        list = List.copyOf(l);
        total = new Consolidated();
        all = list.size() < 2 ? null : overall;
        if (cur >= count()) cur = 0;
    }

    /** The account Claude Code is currently on goes first; the rest keep their order. */
    void set(List<AccountUsage> l) {
        var s = new ArrayList<>(l);
        s.sort(Comparator.comparing((AccountUsage a) -> !a.credential().claudecodeactive()));
        list = s;
        total = AllPage.total(list);
        all = AllPage.page(total, list.size());
        if (cur >= count()) cur = 0;
    }

    /** A click in the button strip: the lower left arrow goes back, anywhere else in the strip goes forward. */
    void click(double x, double y, double h) { page(x < BTN / 2 && y > h * 0.68 ? -1 : 1); }

    void page(int d) {
        if (list.isEmpty()) return;
        cur = ((cur + d) % count() + count()) % count();
        lastClick = System.currentTimeMillis();
    }

    static final double FOOTER_H = 9, FOOTER_MIN_H = 44, TIGHT_GAP = 1;

    /** The gap between bars: the usual one, or a tighter one when the card (47 high at 125% scaling) must also hold the line below them. */
    static double gap(double h) { return h >= 56 ? GAP : TIGHT_GAP; }

    static double rowsHeight(double h) { return 4 * ROW + 3 * gap(h); }

    /** Where the four bars start: centred together with the line below them, when the card is tall enough for it. */
    static double top(double h) { return (h - rowsHeight(h) - (h >= FOOTER_MIN_H ? FOOTER_H : 0)) / 2; }

    /** One static line under the bars: auto or off, and the weekly rate over all accounts. Numbers only. */
    void footer(GraphicsContext g, double h) {
        if (h < FOOTER_MIN_H) return;
        var y = top(h) + rowsHeight(h) + FOOTER_H / 2 + 0.5;
        var x = BTN + 3;
        g.setFont(SMALL);
        g.setTextAlign(TextAlignment.LEFT);
        var mode = modeLabel.get();
        if (freeStyle) {   // a free account has no weekly reset, so no weekly rate: just how many accounts have room
            g.setFill(GREEN);
            g.fillText(mode == null ? "free" : mode, x, y);
            return;
        }
        if (mode != null) {
            g.setFill(mode.equals("auto") ? GREEN : ORANGE);
            g.fillText(mode, x, y);
            x += 25;
        }
        g.setFill(GRAY);
        g.fillText("wk avg", x, y);
        var rate = total.weekRate();
        g.setFill(total.hasWeek() ? rate(rate) : GRAY);
        g.fillText(total.hasWeek() ? pct(rate) : "--", x + 27, y);
    }

    void autoHome() {
        if (cur != 0 && System.currentTimeMillis() - lastClick > HOME_AFTER_MS) cur = 0;
    }

    /** The free accounts' card has its own palette (a blue-green ground, teal time bars), so the two cards are told apart at a glance; the usage colours keep their meaning. */
    static final Color FREE_BG = Color.web("#1D3238"), FREE_BTN = Color.web("#27484F"), FREE_BTN_HOT = Color.web("#34606A"), FREE_TIME = Color.web("#26C6DA"), FREE_WEEK = Color.web("#80DEEA");

    Color blue() { return freeStyle ? FREE_TIME : BLUE; }

    Color sky() { return freeStyle ? FREE_WEEK : SKY; }

    static Color use(double p) { return p >= 80 ? RED : p >= 60 ? ORANGE : GREEN; }
    static Color rate(double r) { return r >= 120 ? RED : r >= 100 ? ORANGE : GREEN; }
    static double clamp(double v) { return Math.max(0, Math.min(100, v)); }

    static double elapsed(OffsetDateTime resets, long windowSecs) {
        if (resets == null) return 0;
        return clamp((windowSecs - Duration.between(OffsetDateTime.now(), resets).getSeconds()) * 100.0 / windowSecs);
    }

    static String weekly(OffsetDateTime resets) {
        if (resets == null) return "N/A";
        var d = Duration.between(OffsetDateTime.now(), resets);
        if (d.isNegative() || d.isZero()) return "Now";
        if (d.toDays() > 0) return d.toHoursPart() == 0 ? "%dd".formatted(d.toDays()) : "%dd%dh".formatted(d.toDays(), d.toHoursPart());
        return d.toHours() > 0 ? "%dh".formatted(d.toHours()) : "%dm".formatted(d.toMinutesPart());
    }

    static String pct(double v) { return "%d%%".formatted(Math.round(v)); }

    void draw(GraphicsContext g, double w, double h) {
        g.setTextBaseline(VPos.CENTER);
        if (list.isEmpty()) { blank(g, w, h, "no data", freeStyle ? FREE_BG : BG); return; }
        var a = current();
        g.setFill(freeStyle ? FREE_BG : a.credential().active() ? BG : BG_OFF);
        g.fillRect(0, 0, w, h);
        side(g, h, a);
        footer(g, h);
        var d = a.usageData();
        if (d == null || d.fiveHour() == null) { g.setFill(GRAY); g.setFont(BOLD); g.setTextAlign(TextAlignment.CENTER); g.fillText("no data", (w + BTN) / 2, h / 2); return; }
        var h5 = a.getFiveHourUsagePercent();
        var s = d.sevenDay();
        var x0 = BTN + 3;
        var top = top(h);
        var pitch = ROW + gap(h);
        row(g, x0, top, w, h5, use(h5), use(h5), pct(a.getUsageToTimeRatio() * 100), rate(a.getUsageToTimeRatio() * 100));
        row(g, x0, top + pitch, w, a.getFiveHourTimeElapsedPercent(), Color.WHITE, blue(), a.getTimeUntilReset(), Color.WHITE);
        if (s == null) { row(g, x0, top + 2 * pitch, w, 0, GRAY, GRAY, "--", GRAY); row(g, x0, top + 3 * pitch, w, 0, GRAY, GRAY, "--", GRAY); return; }
        var wk = s.utilization();
        if (s.resetsAt() == null) {   // no weekly reset known (a free account): the usage, and nothing made up for the pace and the time
            row(g, x0, top + 2 * pitch, w, wk, use(wk), use(wk), "--", GRAY);
            row(g, x0, top + 3 * pitch, w, 0, GRAY, GRAY, "N/A", GRAY);
            return;
        }
        var wkt = elapsed(s.resetsAt(), 7 * 24 * 3600L);
        var ratio = wkt > 0 ? wk / wkt * 100 : 0;
        row(g, x0, top + 2 * pitch, w, wk, use(wk), use(wk), pct(ratio), rate(ratio));
        row(g, x0, top + 3 * pitch, w, wkt, Color.WHITE, sky(), weekly(s.resetsAt()), Color.WHITE);
    }

    void side(GraphicsContext g, double h, AccountUsage a) {
        var cred = a.credential();
        g.setFill(freeStyle ? (hot ? FREE_BTN_HOT : FREE_BTN) : hot ? BTN_HOT : BTN_BG);
        g.fillRect(0, 0, BTN, h);
        g.setTextAlign(TextAlignment.CENTER);
        g.setFont(BOLD);
        var beating = cred.claudecodeactive() && glow;
        var fire = GlowPulse.fire(GlowPulse.danger(a));
        g.setFill(beating ? fire : cred.claudecodeactive() ? CURRENT : Color.WHITE);
        if (beating) { neon.setColor(fire); neon.setRadius(GlowPulse.radius()); g.setEffect(neon); }
        var id = cred.id() == null ? "?" : cred.id();
        g.fillText((id.length() > 3 ? id.substring(0, 3) : id).toUpperCase(), BTN / 2, h * 0.17);
        g.setEffect(null);
        g.setFill(cred.active() ? GREEN : RED);
        g.fillText(cred.active() ? "✔" : "✘", BTN / 2 - 4, h * 0.38);
        g.setFill(Color.YELLOW);
        g.fillText(cred.tier() == 0 ? "F" : String.valueOf(cred.tier()), BTN / 2 + 5, h * 0.38);
        g.setFont(SMALL);
        g.setFill(GRAY);
        g.fillText((cur + 1) + "/" + count(), BTN / 2, h * 0.58);
        g.setFont(BOLD);
        g.setFill(Color.WHITE);
        g.fillText("◀", BTN / 2 - 5.5, h * 0.82);
        g.fillText("▶", BTN / 2 + 5.5, h * 0.82);
    }

    void row(GraphicsContext g, double x0, double y, double w, double p, Color pc, Color bc, String right, Color rc) {
        var cy = y + ROW / 2;
        g.setFont(BOLD);
        g.setTextAlign(TextAlignment.RIGHT);
        g.setFill(pc);
        g.fillText(pct(p), x0 + LABEL, cy);
        var bx = x0 + LABEL + 1;
        var bw = w - bx - RIGHT - 2;
        if (bw > 0) { g.setFill(bc); g.fillRect(bx, cy - BAR / 2, bw * clamp(p) / 100, BAR); }
        g.setTextAlign(TextAlignment.LEFT);
        g.setFill(rc);
        g.fillText(right, Math.max(bx, w - RIGHT), cy);
    }

    static void blank(GraphicsContext g, double w, double h, String text, Color bg) {
        g.setFill(bg);
        g.fillRect(0, 0, w, h);
        g.setFill(GRAY);
        g.setFont(BOLD);
        g.setTextAlign(TextAlignment.CENTER);
        g.fillText(text, w / 2, h / 2);
    }
}
