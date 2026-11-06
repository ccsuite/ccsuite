package ccsuite.usagetracker.taskbar;

import module java.base;
import javafx.application.Platform;
import javafx.scene.Group;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import ccsuite.usagetracker.model.AccountUsage;
import ccsuite.usagetracker.ui.GlowPulse;

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

    void open() {
        stage = new Stage(StageStyle.UNDECORATED);
        stage.setTitle(title);
        stage.setX(-3000);
        stage.setY(0);
        var scene = new Scene(new Group(canvas), 101, 47);
        scene.widthProperty().addListener((o, a, w) -> { canvas.setWidth(w.doubleValue()); draw(); });
        scene.heightProperty().addListener((o, a, h) -> { canvas.setHeight(h.doubleValue()); draw(); });
        scene.setOnMouseMoved(e -> { var hot = e.getX() < CardView.BTN; if (hot != view.hot) { view.hot = hot; draw(); } });
        scene.setOnMouseExited(e -> { view.hot = false; draw(); });
        scene.setOnMousePressed(e -> { if (e.getX() < CardView.BTN) { view.click(e.getX(), e.getY(), canvas.getHeight()); draw(); } });
        scene.setOnScroll(e -> { if (e.getDeltaY() != 0) { view.page(e.getDeltaY() < 0 ? 1 : -1); draw(); } });
        stage.setScene(scene);
        stage.show();
        draw();
    }
}
