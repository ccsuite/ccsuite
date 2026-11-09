package ccsuite.usagetracker.taskbar;

import module java.base;
import javafx.application.Platform;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.animation.PauseTransition;
import javafx.scene.control.Label;
import javafx.scene.input.MouseEvent;
import javafx.stage.Popup;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import ccsuite.usagetracker.model.AccountUsage;
import ccsuite.usagetracker.ui.GlowPulse;
import ccsuite.usagetracker.ui.UsageDetails;

/** The usage card in the taskbar: a JavaFX window parented into the slot DLL's band. Create and call start() on the FX thread. */
public final class TaskbarCard {
    static final String TITLE = "ccu-taskbar-card", FREE_TITLE = "ccu-free-taskbar-card";

    final Canvas canvas = new Canvas(101, 47);
    final CardView view = new CardView();
    final String title;
    final boolean free;
    final Slot slot;
    ScheduledExecutorService timer;
    Stage stage;
    Popup popup;
    volatile List<AccountUsage> latest = List.of();
    volatile AccountUsage latestAll;

    /** The usage card of the Claude Code accounts. */
    public TaskbarCard() { this(TITLE, false); }

    private TaskbarCard(String title, boolean free) {
        this.title = title; this.free = free;
        this.slot = new Slot(title, free);
        view.freeStyle = free;
    }

    /** The card of the free accounts: same look and the same slot, placed before the usage card (above it on a vertical taskbar), ALL as its first page. Fed with {@link #updateFree}. */
    public static TaskbarCard free() {
        SlotLayout.freeCard(true);
        return new TaskbarCard(FREE_TITLE, true);
    }

    public void start() {
        if (!free || Slot.bandCards() == 2) open();   // the free card has no window (and so no taskbar button) until the band can hold it
        timer = Executors.newSingleThreadScheduledExecutor(r -> Thread.ofPlatform().daemon().name("taskbar-card").unstarted(r));
        timer.scheduleWithFixedDelay(() -> Platform.runLater(this::tick), 1, 1, TimeUnit.SECONDS);
        // The beat of the active account's name: a daemon thread asks for a redraw a few times a second,
        // and only when the card is showing that account (no endless JavaFX animation: it keeps the frame clock running).
        timer.scheduleWithFixedDelay(() -> { if (view.glow && slot.embedded) Platform.runLater(this::pulse); },
                GlowPulse.TICK_MS, GlowPulse.TICK_MS, TimeUnit.MILLISECONDS);
    }

    /** The line at the bottom of the card says auto or off from this; leave unset when there is no watcher (ccu alone). Call before {@link #start}. */
    public void modeLabel(java.util.function.Supplier<String> label) { view.modeLabel = label; }

    /** The glow of the active account's name on or off (the {@code glow} setting). Call before {@link #start}. */
    public void glow(boolean on) { view.glow = on; }

    void pulse() { if (view.currentIsActive()) draw(); }

    /** Called from any thread whenever ccu has new data. */
    public void update(List<AccountUsage> list) {
        latest = List.copyOf(list);
        System.out.println("  🧩 taskbar card: " + latest.size() + " account(s), " + (slot.embedded ? "in the taskbar" : "waiting for the taskbar slot"));
        Platform.runLater(() -> { view.set(latest); draw(); });
    }

    /** The free card's data, from any thread: the accounts, and their overall as the first page. */
    public void updateFree(List<AccountUsage> list, AccountUsage overall) {
        latest = List.copyOf(list);
        latestAll = overall;
        Platform.runLater(() -> { view.setFree(latest, latestAll); draw(); });
    }

    public boolean embedded() { return slot.embedded; }

    public void stop() {
        if (timer != null) timer.shutdownNow();
        slot.release();
        if (stage != null) stage.close();
    }

    Function<AccountUsage, String> refresh;
    Consumer<AccountUsage> activate;

    /** What a press on the tier mark does: reads the page's account again now (the owner does it off the FX thread and returns the failure in words, or null; for ALL it reads every account). Silent unless it failed. */
    public void onRefresh(Function<AccountUsage, String> f) { refresh = f; }

    /** What a press on the account name does (the paid card only): the owner asks the user to confirm, then makes that account the active one. */
    public void onActivate(Consumer<AccountUsage> c) { activate = c; }

    void press(double x, double y) {
        var a = view.current();
        switch (CardView.hit(x, y, canvas.getHeight())) {
            case NAME -> { view.lastClick = System.currentTimeMillis(); if (activate != null && !free && a != null && !"ALL".equals(a.credential().id())) activate.accept(a); }
            case TYPE -> { view.lastClick = System.currentTimeMillis(); reread(a); }
            case BACK -> view.page(-1);
            case FORWARD -> view.page(1);
        }
        draw();
    }

    void reread(AccountUsage a) {
        var f = refresh;
        if (f == null || a == null) return;
        Thread.ofPlatform().daemon().name("card-reread").start(() -> {
            var failure = f.apply(a);
            if (failure != null) ccsuite.usagetracker.ui.ReadFailed.show(a.credential().id(), failure);
        });
    }

    void draw() { view.draw(canvas.getGraphicsContext2D(), canvas.getWidth(), canvas.getHeight()); }

    void tick() {
        if (stage == null) {   // the free card, waiting for the two-card band
            if (Slot.bandCards() != 2) return;
            open();
        }
        if (!slot.embedded) slot.embed();
        else if (!slot.alive()) { slot.embedded = false; stage.close(); if (free) stage = null; else open(); return; }
        else slot.place(false);
        if (free) view.setFree(latest, latestAll); else view.set(latest);
        view.autoHome();
        draw();
    }


    /**
     * The same details popup the usage window shows on hover, for the page on show (an account, or ALL), paid and free alike. A JavaFX Tooltip is not used: it
     * appears only for a focused window, and this one lives in the taskbar and never takes the focus. A timer and a Popup (which takes no focus either) do it.
     */
    void hover() {
        if (popup != null) return;
        var text = new Label();
        text.setStyle(UsageDetails.POPUP_STYLE);
        popup = new Popup();
        popup.getContent().add(text);
        var wait = new PauseTransition(javafx.util.Duration.millis(500));
        var at = new double[2];
        wait.setOnFinished(e -> {
            var s = stage;
            if (s == null) return;
            text.setText(UsageDetails.text(view.current()));
            popup.show(s, at[0] + 14, at[1] - 10);
            var b = Screen.getScreensForRectangle(at[0], at[1], 1, 1).stream().findFirst().orElse(Screen.getPrimary()).getVisualBounds();
            popup.setX(Math.max(b.getMinX(), Math.min(popup.getX(), b.getMaxX() - popup.getWidth())));
            popup.setY(Math.max(b.getMinY(), Math.min(popup.getY(), b.getMaxY() - popup.getHeight())));
        });
        canvas.addEventHandler(MouseEvent.MOUSE_MOVED, e -> { at[0] = e.getScreenX(); at[1] = e.getScreenY(); if (!popup.isShowing()) wait.playFromStart(); });
        canvas.addEventHandler(MouseEvent.MOUSE_EXITED, e -> { wait.stop(); popup.hide(); });
        canvas.addEventHandler(MouseEvent.MOUSE_PRESSED, e -> { wait.stop(); popup.hide(); });
    }

    void open() {
        stage = new Stage(StageStyle.UNDECORATED);
        stage.setTitle(title);
        stage.setX(-3000);
        stage.setY(0);
        hover();
        var scene = new Scene(new Group(canvas), 101, 47);
        scene.widthProperty().addListener((o, a, w) -> { canvas.setWidth(w.doubleValue()); draw(); });
        scene.heightProperty().addListener((o, a, h) -> { canvas.setHeight(h.doubleValue()); draw(); });
        scene.setOnMouseMoved(e -> { var hot = e.getX() < CardView.BTN; if (hot != view.hot) { view.hot = hot; draw(); } });
        scene.setOnMouseExited(e -> { view.hot = false; draw(); });
        scene.setOnMousePressed(e -> { if (e.getX() < CardView.BTN) press(e.getX(), e.getY()); });
        scene.setOnScroll(e -> { if (e.getDeltaY() != 0) { view.page(e.getDeltaY() < 0 ? 1 : -1); draw(); } });
        stage.setScene(scene);
        stage.show();
        draw();
    }
}
