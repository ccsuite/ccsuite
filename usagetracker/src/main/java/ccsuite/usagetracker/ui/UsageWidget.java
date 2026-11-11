package ccsuite.usagetracker.ui;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Line;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;
import ccsuite.base.config.CredentialsManager;
import ccsuite.base.model.CCAPICredential;
import ccsuite.base.model.CCAPIsCredentials;
import ccsuite.base.model.UsageData;
import ccsuite.oauthswap.usage.OAuthUsageSource;
import ccsuite.webclient.WebUsageSource;
import ccsuite.usagetracker.config.Settings;
import ccsuite.usagetracker.config.SettingsManager;
import ccsuite.usagetracker.model.AccountUsage;

import java.awt.*;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * JavaFX floating widget for usage tracking
 * Compact, always-on-top display with progress bars and system tray integration
 */
public class UsageWidget extends Application {

    // Removed: REFRESH_INTERVAL_SECONDS - now configurable via settings (usageRefreshIntervalSecs)
    private static final double WIDGET_WIDTH = 120; // Increased to accommodate v2 layout with weekly indicators
    private static final double TITLE_BAR_HEIGHT = 30;
    private static final double ROW_HEIGHT = 32;
    private static final double MIN_HEIGHT = 40;
    private static final double MAX_HEIGHT = 400;
    private static final double BTN_COL_WIDTH = 20;  // Button column width in side-bar mode
    private static final double GRIP_WIDTH = 12;     // Drag-gap strip to the right of the buttons
    private static final double LEFT_BAR_WIDTH = BTN_COL_WIDTH + GRIP_WIDTH; // Total left region
    private static final double SIDE_BTN_HEIGHT = 12; // Compact button height in side-bar mode
    private static final double SIDE_MIN_HEIGHT = 44; // Min window height in side-bar mode (fits 3 buttons)

    private VBox contentBox;
    private VBox accountsContainer;
    private ScrollPane scrollPane;
    private SettingsManager settingsManager;
    private CredentialsManager credentialsManager;
    private WebUsageSource apiClient;
    // Fallback usage source: minimal Haiku completion read for anthropic-ratelimit-unified-*
    // headers, used when apiClient's normal fetch fails. See tryInferenceProbeFallback (manual
    // button) and tryAutoProbeFallback (periodic sweep). Cooldown gating (so a repeated failure
    // doesn't hammer real tokens) lives on the OAuthUsageSource, shared by both
    // call paths — see ccsuite.oauthswap.usage.OAuthUsageSource#fetchIfDue.
    private OAuthUsageSource probeSource;
    // Set by a host app that owns the tray and shares one usage source across its parts.
    private OAuthUsageSource sharedSource;
    private boolean hosted;
    // A feed window is the same window with the pages pushed in by its owner instead of fetched here (the free accounts' window): its own title, colours and settings file.
    private boolean feed;
    private String windowTitle = "CCUsage";
    private Palette palette = Palette.PAID;
    private java.nio.file.Path feedSettings;
    private java.util.function.Function<AccountUsage, String> feedRefresh;
    private AccountUsage feedAll;
    // A feed window has no taskbar setting of its own; its host says whether a taskbar card shows these pages (then the window waits to be asked for).
    private boolean feedCard;
    // True while the window stands where {@link WidgetPlace} put it because no place was remembered: it then follows its size (a row more, the bar moved) and stays bottom right.
    private boolean placedByDefault;
    // Who Claude Code is logged into right now (the host's live detection); the file's claudecodeactive flag
    // is only as fresh as the last tool that wrote it, so when this is set it decides.
    private java.util.function.Supplier<String> liveActiveId;
    // The beat of the active account's name in the rows: a daemon thread asks for a redraw a few times a second
    // while the window is showing (not an endless JavaFX animation, which would keep the frame clock running).
    private ScheduledExecutorService glowTimer;
    private ScheduledExecutorService liveTimer;
    private volatile boolean windowShown;
    // "auto" or "off" for the line under the card's bars; the host says it (null without a watcher, as in ccu alone)
    private java.util.function.Supplier<String> modeLabel;
    private Settings settings;
    private CCAPIsCredentials credentials;
    private Settings.UISettings uiSettings;
    private Stage primaryStage;
    private TrayIcon trayIcon;
    private int lastAccountCount = -1; // Track account count to avoid unnecessary resizing
    private boolean userManuallyResized = false; // Track if user manually resized window
    private boolean programmaticHeightChange = false; // Track if height change is programmatic
    private boolean sideBarMode = false; // true = vertical move/button bar on the left (toggled from tray)

    // Per-account controllers, so a refresh sweep can update each row in place (no flicker/rebuild)
    private final java.util.Map<String, UsageWidgetController> rowControllers = new java.util.LinkedHashMap<>();
    // Latest fetched data per account — tooltips read from here so they always show fresh values
    private final java.util.Map<String, AccountUsage> latestAccountUsages = new java.util.LinkedHashMap<>();
    // Tooltip per account — updated eagerly on every fetch so text is always fresh
    private final java.util.Map<String, Tooltip> rowTooltips = new java.util.LinkedHashMap<>();
    private java.util.List<String> lastRowIds = null; // Account-id set currently rendered (rebuild only when it changes)

    // Single background thread for all refresh sweeps (periodic + the title-bar refresh button)
    private ScheduledExecutorService refreshExecutor;

    /** The taskbar card is a Windows shell extension; on other systems the floating window is the display. */
    public static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase().contains("win");

    // The usage card hosted in the Windows taskbar (null while the taskbar option is off)
    private ccsuite.usagetracker.taskbar.TaskbarCard taskbarCard;

    @Override
    public void start(Stage primaryStage) {
        this.primaryStage = primaryStage;
        settingsManager = feed ? new SettingsManager(feedSettings) : new SettingsManager();

        // Load UI settings
        try {
            settings = settingsManager.load();
            uiSettings = settings.getUiSettings();
            sideBarMode = uiSettings.sideBar();
        } catch (Exception e) {
            showError("Failed to load UI settings: " + e.getMessage());
            return;
        }

        if (!feed) {
        // Load shared credentials
        credentialsManager = new CredentialsManager();
        try {
            credentials = credentialsManager.load();
            markLiveActive();
        } catch (Exception e) {
            showError("Failed to load credentials: " + e.getMessage());
            return;
        }

        // Validate credentials
        if (credentials.getPaidUsageCredentials().isEmpty()) {
            showError("No credentials with trackUsage=true found");
            return;
        }

        // Create API client (stateless - baseUrl is per-credential)
        apiClient = new WebUsageSource();

        // Fallback client (see tryInferenceProbeFallback / tryAutoProbeFallback below).
        probeSource = sharedSource != null ? sharedSource : OAuthUsageSource.standard();
        }
        // Create UI - minimal padding per PRP requirements
        contentBox = new VBox(0);
        contentBox.setPadding(new Insets(1, 1, 3, 1)); // Extra padding at bottom for resize area
        contentBox.setStyle("-fx-background-color: rgba(30, 30, 30, 0.95); -fx-background-radius: 5;");

        // Create accounts container (will hold all account rows)
        accountsContainer = new VBox(0);
        accountsContainer.setPadding(Insets.EMPTY);

        // Create scroll pane for accounts
        scrollPane = new ScrollPane(accountsContainer);
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollPane.setPadding(Insets.EMPTY);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);

        // Style scrollbar to match theme - thin dark scrollbar with blue thumb
        scrollPane.setStyle(
            "-fx-background: transparent; " +
            "-fx-background-color: transparent; " +
            "-fx-focus-color: transparent; " +
            "-fx-faint-focus-color: transparent;"
        );

        // Apply custom CSS for scrollbar styling
        var scrollbarCss = getClass().getResource("/ccsuite/usagetracker/ui/scrollbar.css");
        if (scrollbarCss != null) {
            scrollPane.getStylesheets().add(scrollbarCss.toExternalForm());
        }

        var scene = new Scene(contentBox, WIDGET_WIDTH, MIN_HEIGHT);
        scene.setFill(Color.TRANSPARENT);

        // Configure stage
        primaryStage.initStyle(StageStyle.TRANSPARENT);
        primaryStage.setAlwaysOnTop(true);
        primaryStage.setTitle(feed ? windowTitle : "CCAPI Usage Tracker");
        primaryStage.getIcons().setAll(UsageAppIcon.windowIcons());
        primaryStage.setScene(scene);
        primaryStage.setMinHeight(currentMinHeight());
        primaryStage.setMaxHeight(MAX_HEIGHT);
        primaryStage.setResizable(true);

        // Lock width (wider by the bar in side-bar mode), only allow vertical resizing
        applyWidthLock();

        // Save window height when user resizes (but not on programmatic changes)
        primaryStage.heightProperty().addListener((obs, oldVal, newVal) -> {
            if (primaryStage.isShowing() && !oldVal.equals(newVal) && !programmaticHeightChange && !placedByDefault) {   // while it stands where WidgetPlace put it, only a move or resize BY THE USER (which saves by itself) makes the place remembered
                saveWindowPosition();
            }
        });

        // Enable custom resize since transparent windows don't have native resize handles
        // Bottom edge resize on contentBox
        enableResizeBottom(primaryStage, contentBox);

        // Setup system tray (a host app has its own)
        if (!hosted) setupSystemTray();

        // Handle window close request
        primaryStage.setOnCloseRequest(event -> {
            event.consume(); // Prevent default close
            saveWindowPosition(); // Save position before hiding
            hideToTray();
        });

        // Set window position from settings before showing
        setWindowPosition(primaryStage);

        // Show loading placeholder immediately (so window is visible and draggable)
        showLoadingPlaceholder();

        // Show window first (before any network calls). It is optional: with floatingWindow=false the
        // taskbar card is the display, and the tray menu still opens this window on demand.
        if (uiSettings.floatingWindow() && (!hosted || !cardWanted())) {   // a host (ccacc) shows it by itself unless the taskbar card is the display; then only when asked: its tray, its window, or the slot staying absent
            primaryStage.show();
        }

        // Initial update - run asynchronously to avoid blocking UI thread
        if (feed) return;   // a feed window fetches nothing: its owner pushes the pages (see feed)
        refreshExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, "usage-refresh");
            t.setDaemon(true);
            return t;
        });
        refreshExecutor.submit(this::safeUpdate);

        // Schedule periodic updates using configurable refresh interval.
        // Fixed DELAY (not fixed rate) guarantees a real gap between sweeps so a slow
        // sweep never piles up / overlaps with the next one.
        var refreshInterval = uiSettings.usageRefreshIntervalSecs();
        refreshExecutor.scheduleWithFixedDelay(
                this::safeUpdate,
                refreshInterval,
                refreshInterval,
                TimeUnit.SECONDS
        );

        startGlow();
        startLiveFollow();
        if (uiSettings.taskbar() && WINDOWS) {
            startTaskbar();
        }
    }

    /**
     * Taskbar card: extract and register the slot DLL (per user, no admin), then host the card in
     * the slot. It is fed by this widget's own refresh sweep, so nothing extra is fetched.
     */
    /** True when the taskbar card is what shows these pages (the setting is on, on Windows); the floating window then waits to be asked for. */
    private boolean cardWanted() { return WINDOWS && (feed ? feedCard : uiSettings.taskbar()); }

    private void startTaskbar() {
        if (taskbarCard != null) return;
        try {
            System.out.println("🧩 Taskbar slot registered: " + ccsuite.usagetracker.taskbar.SlotInstaller.install());
        } catch (Exception e) {
            System.err.println("⚠️  Taskbar slot could not be installed: " + e.getMessage());
        }
        taskbarCard = new ccsuite.usagetracker.taskbar.TaskbarCard();
        taskbarCard.glow(uiSettings.glow());
        if (modeLabel != null) taskbarCard.modeLabel(modeLabel);
        taskbarCard.onRefresh(this::rereadFromCard);
        taskbarCard.onActivate(page -> { var h = activateHandler; if (h != null) Platform.runLater(() -> h.accept(page.credential().id())); });
        taskbarCard.start();
        pushTaskbar();
        if (!primaryStage.isShowing()) {
            // Never leave the user with nothing: if the slot is not in the taskbar yet (the band must be
            // ticked once in the taskbar's Toolbars menu, and Explorer restarted if it was already running),
            // open the floating window after a while.
            var wait = new javafx.animation.PauseTransition(javafx.util.Duration.seconds(90));
            wait.setOnFinished(e -> {
                if (taskbarCard != null && !taskbarCard.embedded() && !primaryStage.isShowing()) {
                    System.out.println("🧩 The taskbar slot is not available yet - showing the floating window");
                    showFromTray();
                }
            });
            wait.play();
        }
    }

    /**
     * A window that shows pages its owner pushes ({@link #feed(List, AccountUsage)}) instead of fetching them: the same rows, ALL row, hover and chrome, in the colours of
     * {@code palette}. {@code refresh} reads one page's account again (ALL: every account) and returns the failure in words or null; it runs off the FX thread.
     * Not shown by itself; its position is kept in {@code settingsFile}.
     */
    public static UsageWidget feed(String title, Palette palette, java.nio.file.Path settingsFile, java.util.function.Function<AccountUsage, String> refresh) {
        var w = new UsageWidget();
        w.feed = true; w.hosted = true;
        w.windowTitle = title; w.palette = palette; w.feedSettings = settingsFile; w.feedRefresh = refresh;
        return w;
    }

    /** Whether the settings file asks for the taskbar card (the free accounts' card follows it, since the setting belongs to the usage widget); false off Windows or when the file cannot be read. */
    public static boolean cardSetting() {
        if (!WINDOWS) return false;
        try { return new SettingsManager().load().getUiSettings().taskbar(); }
        catch (Exception e) { return false; }
    }

    /** Call before {@link #start}: the host shows these pages in a taskbar card, so the window is not shown by itself. */
    public UsageWidget cardInTaskbar(boolean on) { this.feedCard = on; return this; }

    /** The owner's pages and their ALL page (null for none): the rows are built when the set of accounts changes and updated in place otherwise. Any thread. */
    public void feed(List<AccountUsage> pages, AccountUsage all) {
        Platform.runLater(() -> {
            if (!feed || primaryStage == null || accountsContainer == null) return;
            feedAll = all;
            var ids = pages.stream().map(p -> p.credential().id()).toList();
            if (!ids.equals(lastRowIds)) { displayUsageData(pages); lastRowIds = ids; }
            for (var p : pages) {
                latestAccountUsages.put(p.credential().id(), p);
                var controller = rowControllers.get(p.credential().id());
                if (controller != null) controller.updateUsageData(p, uiSettings.showTime());
                var tooltip = rowTooltips.get(p.credential().id());
                if (tooltip != null) tooltip.setText(buildTooltipText(p));
            }
            showAll(all);
        });
    }

    /** A row's tier mark or refresh button, or the title bar's: read that account again; a failure is shown, success is silent. Off the FX thread. */
    private void rereadRow(AccountUsage page) {
        var failure = feed ? (feedRefresh == null ? null : feedRefresh.apply(page)) : rereadFromCard(page);
        if (failure != null) ReadFailed.show(page.credential().id(), failure);
    }

    private java.util.function.Consumer<String> activateHandler;

    /** What a press on an account's name does (the host confirms with the user, then switches); without it the name is not a button. */
    public UsageWidget onActivate(java.util.function.Consumer<String> handler) { this.activateHandler = handler; return this; }

    /** The tier mark of the card was pressed: read that account again now (every account for ALL). Returns the failure in words, or null. Off the FX thread. */
    private String rereadFromCard(AccountUsage page) {
        var id = page.credential().id();
        if ("ALL".equals(id)) { safeUpdate(); return null; }
        var live = credentials == null ? null : credentials.getById(id);
        var c = live != null ? live : page.credential();
        try {
            var data = c.key() == null || c.key().isBlank() ? null
                : apiClient.fetchUsageWithRetry(c, uiSettings.usageMaxRetries(), uiSettings.usageRetryBaseMs(), uiSettings.usageRetryMaxMs());
            if (data == null) data = tryInferenceProbeFallback(c);
            if (data == null) return "Nothing could be read for " + id + " just now (it has no session key, and its login was read a moment ago).";
            applyFetchedUsage(c, data, rowControllers.get(id));
            return null;
        } catch (Exception e) {
            var fallback = tryInferenceProbeFallback(c);
            if (fallback == null) return String.valueOf(e.getMessage());
            applyFetchedUsage(c, fallback, rowControllers.get(id));
            return null;
        }
    }

    private void stopTaskbar() {
        if (taskbarCard == null) return;
        taskbarCard.stop();
        taskbarCard = null;
    }

    private void pushTaskbar() {
        var card = taskbarCard;
        if (card != null) card.update(new java.util.ArrayList<>(latestAccountUsages.values()));
    }

    private void setTaskbarMode(boolean on) {
        if (on && !WINDOWS) return;
        uiSettings.taskbar(on);
        saveSettings();
        if (on) startTaskbar(); else stopTaskbar();
    }

    private void setFloatingMode(boolean on) {
        uiSettings.floatingWindow(on);
        saveSettings();
        if (on) showFromTray(); else hideToTray();
    }

    /**
     * Crash-proof wrapper for the periodic task. An uncaught exception here would make
     * scheduleWithFixedDelay silently cancel ALL future runs (the "nothing refreshes
     * anymore" symptom), so we must swallow everything.
     */
    private void safeUpdate() {
        try {
            updateUsageData();
        } catch (Throwable t) {
            System.err.println("⚠️  Usage refresh cycle failed (will retry next interval): " + t.getMessage());
        }
    }

    private void setupSystemTray() {
        // Check if system tray is supported
        if (!SystemTray.isSupported()) {
            System.err.println("System tray is not supported");
            return;
        }

        Platform.setImplicitExit(false); // Keep app running when window is hidden

        // Create tray icon image
        var trayImage = UsageAppIcon.trayImage();

        // Create popup menu
        var popup = new PopupMenu();

        var showItem = new MenuItem("Show");
        showItem.addActionListener(e -> Platform.runLater(this::showFromTray));

        var hideItem = new MenuItem("Hide");
        hideItem.addActionListener(e -> Platform.runLater(this::hideToTray));

        var settingsItem = new MenuItem("Open Settings File");
        settingsItem.addActionListener(e -> openSettingsInNotepad());

        var exitItem = new MenuItem("Exit");
        exitItem.addActionListener(e -> Platform.runLater(this::exitApp));

        // Toggle: move/button bar on the left (compact) vs. the top
        var sideBarItem = new CheckboxMenuItem("Bar on left (compact)", sideBarMode);
        sideBarItem.addItemListener(e -> {
            var on = sideBarItem.getState();
            Platform.runLater(() -> setSideBarMode(on));
        });

        // Taskbar card and floating window are independent options
        var taskbarItem = new CheckboxMenuItem("Taskbar card", uiSettings.taskbar());
        taskbarItem.addItemListener(e -> {
            var on = taskbarItem.getState();
            Platform.runLater(() -> setTaskbarMode(on));
        });
        var floatingItem = new CheckboxMenuItem("Floating window", uiSettings.floatingWindow());
        floatingItem.addItemListener(e -> {
            var on = floatingItem.getState();
            Platform.runLater(() -> setFloatingMode(on));
        });

        popup.add(showItem);
        popup.add(hideItem);
        popup.addSeparator();
        popup.add(taskbarItem);
        popup.add(floatingItem);
        popup.addSeparator();
        popup.add(settingsItem);
        popup.add(sideBarItem);
        popup.addSeparator();
        popup.add(exitItem);

        // Create tray icon
        trayIcon = new TrayIcon(trayImage, "CCAPI Usage Tracker", popup);
        trayIcon.setImageAutoSize(true);

        // Double-click to show/hide
        trayIcon.addActionListener(e -> Platform.runLater(() -> {
            if (primaryStage.isShowing()) {
                hideToTray();
            } else {
                showFromTray();
            }
        }));

        try {
            SystemTray.getSystemTray().add(trayIcon);
        } catch (AWTException e) {
            System.err.println("Failed to add tray icon: " + e.getMessage());
        }
    }

    private void hideToTray() {
        primaryStage.hide();
    }

    /** The user hid the window: a host remembers it (the window would be shown by itself at the next start otherwise); standalone, the tray's own Show brings it back. */
    private void hidden() {
        if (hosted) setFloatingMode(false); else hideToTray();
    }

    // ---- Hosting: a host app (the combined desktop app) starts this widget on a Stage of its own,
    // keeps the tray icon itself, and shares one usage source. Everything below runs on the FX thread.

    /**
     * The host tells the widget who Claude Code is on now: right after a switch it made, or when it saw one. The card
     * re-sorts and the glow moves at once, without waiting for the next usage sweep. Any thread.
     */
    public void activeChanged(String activeId) {
        var creds = credentials;
        if (creds != null) LiveActive.apply(creds, activeId);
        latestAccountUsages.values().forEach(a -> a.credential().claudecodeactive(activeId != null && activeId.equals(a.credential().id())));
        pushTaskbar();
    }

    /** Call before {@link #start}: no tray icon of the widget's own, and {@code shared} for the fallback probes. */
    public UsageWidget hostedBy(OAuthUsageSource shared, java.util.function.Supplier<String> liveActiveId, java.util.function.Supplier<String> modeLabel) {
        this.hosted = true;
        this.sharedSource = shared;
        this.liveActiveId = liveActiveId;
        this.modeLabel = modeLabel;
        return this;
    }

    public boolean windowShown() { return primaryStage != null && primaryStage.isShowing(); }

    public void showWindow() { if (primaryStage != null) showFromTray(); }

    public void hideWindow() { if (primaryStage != null) hideToTray(); }

    public boolean taskbarOn() { return uiSettings != null && uiSettings.taskbar(); }

    public void taskbar(boolean on) { if (uiSettings != null) setTaskbarMode(on); }

    public boolean floatingOn() { return primaryStage != null && primaryStage.isShowing(); }

    public void floating(boolean on) { if (uiSettings != null) setFloatingMode(on); }

    public boolean sideBar() { return sideBarMode; }

    public void sideBar(boolean on) { if (primaryStage != null) setSideBarMode(on); }

    public void openSettingsFile() { openSettingsInNotepad(); }

    /** A full sweep now, on the refresh thread. */
    public void refreshNow() { if (refreshExecutor != null) refreshExecutor.submit(this::safeUpdate); }

    /** Stops the refresh sweeps and the taskbar card without ending the process. */
    public void shutdown() {
        stopTaskbar();
        if (refreshExecutor != null) refreshExecutor.shutdownNow();
        if (glowTimer != null) glowTimer.shutdownNow();
        if (liveTimer != null) liveTimer.shutdownNow();
        if (primaryStage != null) primaryStage.hide();
    }

    private void showFromTray() {
        primaryStage.show();
        primaryStage.toFront();
    }

    /**
     * Show loading placeholder so window is immediately visible and draggable
     */
    private void showLoadingPlaceholder() {
        accountsContainer.getChildren().clear();
        var loadingLabel = new Label("Loading...");
        loadingLabel.setStyle("-fx-text-fill: " + palette.accent() + "; -fx-font-size: 9px; -fx-padding: 10;");
        accountsContainer.getChildren().add(loadingLabel);
        installChrome();
    }

    /**
     * Build the move/resize bar holding the 3 buttons.
     * vertical=false → horizontal top bar with the "CCUsage" title.
     * vertical=true  → compact vertical bar on the left edge, title omitted.
     */
    private Region createBar(boolean vertical) {
        // Refresh: reload credentials + re-fetch every account (same sweep as startup).
        var refreshBtn = barButton("⟳", "#404040", 11);
        refreshBtn.setOnAction(e -> triggerFullRefresh(refreshBtn));
        var hideBtn = barButton("−", "#404040", 10);
        hideBtn.setOnAction(e -> hidden());
        var closeBtn = barButton("×", "#ff4444", 12);
        closeBtn.setOnAction(e -> { if (hosted) hidden(); else exitApp(); });   // a host owns the process: its window only hides

        Region bar;
        if (vertical) {
            // Compact buttons: no side padding (so the glyph isn't truncated in the narrow column)
            // and a short fixed height (so all 3 fit even when the widget is resized very short).
            styleSideButton(refreshBtn, "#404040", 11);
            styleSideButton(hideBtn, "#404040", 12);
            styleSideButton(closeBtn, "#ff4444", 11);
            // Buttons stacked in a narrow column on the far left.
            var buttons = new VBox(1, refreshBtn, hideBtn, closeBtn);
            buttons.setAlignment(Pos.TOP_CENTER);
            buttons.setPadding(new Insets(1, 1, 1, 1));
            buttons.setMinWidth(BTN_COL_WIDTH);
            buttons.setPrefWidth(BTN_COL_WIDTH);
            buttons.setMaxWidth(BTN_COL_WIDTH);
            // Drag-gap: a full-height strip to the RIGHT of the buttons, before the usage UI starts.
            var grip = new Region();
            grip.setMinWidth(GRIP_WIDTH);
            grip.setPrefWidth(GRIP_WIDTH);
            grip.setMaxWidth(GRIP_WIDTH);
            grip.setMaxHeight(Double.MAX_VALUE);
            grip.setCursor(javafx.scene.Cursor.MOVE);
            grip.setStyle("-fx-background-color: #4a4a4a; -fx-background-radius: 3;");
            Tooltip.install(grip, new Tooltip("Drag to move • top edge resizes"));
            var leftBar = new HBox(0, buttons, grip);
            leftBar.setMaxHeight(Double.MAX_VALUE);
            leftBar.setPickOnBounds(true); // whole left region catches mouse events, not just the buttons
            leftBar.setStyle("-fx-background-color: rgba(45, 45, 45, 0.6);");
            bar = leftBar;
        } else {
            var title = new Label(windowTitle);
            title.setStyle("-fx-text-fill: " + palette.accent() + "; -fx-font-size: 10px; -fx-font-weight: bold;");
            var spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            var h = new HBox(3, title, spacer, refreshBtn, hideBtn, closeBtn);
            h.setAlignment(Pos.CENTER_LEFT);
            h.setPadding(new Insets(2));
            h.setPrefHeight(TITLE_BAR_HEIGHT);
            h.setMinHeight(TITLE_BAR_HEIGHT);
            h.setMaxHeight(TITLE_BAR_HEIGHT);
            bar = h;
        }

        // Make the bar both draggable (move) and resizable from its top edge
        makeHeaderInteractive(primaryStage, bar);
        return bar;
    }

    private static Button barButton(String text, String bg, int fontPx) {
        var b = new Button(text);
        b.setStyle("-fx-background-color: " + bg + "; -fx-text-fill: white; -fx-font-size: "
                + fontPx + "px; -fx-padding: 0 4 0 4; -fx-cursor: hand;");
        return b;
    }

    /** Restyle a button for the narrow vertical bar: zero side padding + short fixed height. */
    private static void styleSideButton(Button b, String bg, int fontPx) {
        b.setStyle("-fx-background-color: " + bg + "; -fx-text-fill: white; -fx-font-size: "
                + fontPx + "px; -fx-padding: 0; -fx-cursor: hand;");
        b.setMaxWidth(Double.MAX_VALUE);
        b.setMinHeight(SIDE_BTN_HEIGHT);
        b.setPrefHeight(SIDE_BTN_HEIGHT);
        b.setMaxHeight(SIDE_BTN_HEIGHT);
    }

    /** (Re)build the chrome (move/resize bar + scroll area) for the current bar mode. */
    private void installChrome() {
        contentBox.getChildren().clear();
        if (sideBarMode) {
            var body = new HBox(0, createBar(true), scrollPane);
            HBox.setHgrow(scrollPane, Priority.ALWAYS);
            VBox.setVgrow(body, Priority.ALWAYS);
            contentBox.getChildren().add(body);
        } else {
            contentBox.getChildren().addAll(createBar(false), scrollPane);
        }
    }

    /** Toggle the move-bar between top and left, persist it, and rebuild the chrome live. */
    private void setSideBarMode(boolean on) {
        sideBarMode = on;
        uiSettings.sideBar(on);
        saveSettings();
        applyWidthLock();
        var min = currentMinHeight();
        primaryStage.setMinHeight(min);
        if (primaryStage.getHeight() < min) {
            programmaticHeightChange = true;
            primaryStage.setHeight(min);
            programmaticHeightChange = false;
        }
        installChrome();
        if (placedByDefault) placeByDefault(primaryStage);
    }

    /** Lock the (fixed) window width for the current mode — wider by the bar width in side-bar mode. */
    private void applyWidthLock() {
        var w = sideBarMode ? WIDGET_WIDTH + LEFT_BAR_WIDTH : WIDGET_WIDTH;
        primaryStage.setMinWidth(w);
        primaryStage.setMaxWidth(w);
        primaryStage.setWidth(w);
    }

    /** Minimum window height: side-bar mode needs enough room to stack the 3 buttons. */
    private double currentMinHeight() {
        return sideBarMode ? SIDE_MIN_HEIGHT : MIN_HEIGHT;
    }

    private void exitApp() {
        stopTaskbar();
        if (trayIcon != null) SystemTray.getSystemTray().remove(trayIcon);
        Platform.exit();
        System.exit(0);
    }

    /**
     * Title-bar refresh: kick a full sweep (reload XML + re-fetch all accounts) on the
     * background thread, just like startup. The button is disabled until the sweep finishes.
     */
    private void triggerFullRefresh(Button refreshBtn) {
        if (feed) {
            refreshBtn.setDisable(true);
            Thread.ofPlatform().daemon().name("feed-reread").start(() -> {
                try { rereadRow(allPage(List.of())); } finally { Platform.runLater(() -> refreshBtn.setDisable(false)); }
            });
            return;
        }
        if (refreshExecutor == null) return;
        refreshBtn.setDisable(true);
        refreshExecutor.submit(() -> {
            try {
                safeUpdate();
            } finally {
                Platform.runLater(() -> refreshBtn.setDisable(false));
            }
        });
    }

    /**
     * Refresh usage data for a single account only.
     * Updates the UI for just this account without fetching data for others.
     */
    private void refreshSingleAccount(CCAPICredential credential, HBox rowContainer, UsageWidgetController controller) {
        var credId = credential.id();
        System.out.println("\n┌─────────────────────────────────────────────────────────────┐");
        System.out.println("│ 🔄 SINGLE ACCOUNT REFRESH - " + credId + "                          │");
        System.out.println("└─────────────────────────────────────────────────────────────┘");

        // Validate credential
        var baseUrl = credential.resolvedCcapiBaseUrl();
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            System.err.println("  ⚠️  Cannot refresh - ccapiBaseUrl is not set");
            return;
        }

        /*if (!credential.active()) {
            System.out.println(" Forced refresh - credential is inactive");
            return;
        }*/

        try {
            System.out.println("  🌐 Fetching from: " + baseUrl);
            var usageData = apiClient.fetchUsageWithRetry(credential,
                    uiSettings.usageMaxRetries(), uiSettings.usageRetryBaseMs(), uiSettings.usageRetryMaxMs());
            applyFetchedUsage(credential, usageData, controller);
            System.out.println("  ✅ Account refreshed successfully");
        } catch (Exception primaryError) {
            System.err.println("  ❌ Normal method failed - " + primaryError.getMessage());

            var fallbackUsageData = tryInferenceProbeFallback(credential);
            if (fallbackUsageData != null) {
                applyFetchedUsage(credential, fallbackUsageData, controller);
                System.out.println("  ✅ Account refreshed via fallback");
            } else {
                System.err.println("  ❌ Fallback also unavailable - keeping last known data");
                var accountUsage = lastKnownOrBlank(credential);
                Platform.runLater(() -> controller.updateUsageData(accountUsage, uiSettings.showTime()));
            }
        }
        System.out.println("└─────────────────────────────────────────────────────────────┘\n");
    }

    /**
     * Apply freshly fetched usage data to this account's row: build the AccountUsage, log a
     * one-line summary, push it to the UI/tooltip, and notify the ping service. Shared by both
     * the normal fetch and the inference-probe fallback in {@link #refreshSingleAccount}.
     */
    private void applyFetchedUsage(CCAPICredential credential, UsageData usageData, UsageWidgetController controller) {
        var accountUsage = new AccountUsage(credential, usageData);

        if (usageData != null && usageData.fiveHour() != null) {
            System.out.println("  ✅ Success - Usage: " + String.format("%.1f%%", usageData.fiveHour().utilization()));
        } else {
            System.out.println("  ✅ Success - No usage data available");
        }

        latestAccountUsages.put(credential.id(), accountUsage);
        pushTaskbar();
        var all = allNow();
        Platform.runLater(() -> {
            if (controller != null) controller.updateUsageData(accountUsage, uiSettings.showTime());
            var tooltip = rowTooltips.get(credential.id());
            if (tooltip != null) {
                tooltip.setText(buildTooltipText(accountUsage));
            }
            showAll(all);
        });
    }

    /**
     * Manual-refresh-only fallback: when the normal CCAPI usage endpoint fails, derive usage
     * from the {@code anthropic-ratelimit-unified-*} headers on a minimal (real, token-costing)
     * Haiku completion instead. Called from {@link #refreshSingleAccount} — a human clicked that
     * account's refresh button and the normal route already failed.
     *
     * <p>Rate-limited to one attempt per account per {@link
     * ccsuite.oauthswap.usage.UsageService#MANUAL_FALLBACK_COOLDOWN_MS} — a courtesy
     * cap against rapid re-clicks, not the primary cost control (the real control is that this
     * path is manual-only; per-call cost is ~9 tokens, negligible against any plan's quota).
     *
     * @return converted usage data, or {@code null} if the fallback itself is unavailable/fails/on cooldown
     */
    private UsageData tryInferenceProbeFallback(CCAPICredential credential) {
        if (probeSource == null) return null;
        var id = credential.id();
        try {
            var probed = probeSource.fetchIfDue(id,
                    ccsuite.oauthswap.usage.UsageService.MANUAL_FALLBACK_COOLDOWN_MS);
            if (probed == null) {
                System.err.println("  ⏳ Fallback probe on cooldown (or unavailable) for " + id + " - skipping repeated click");
                return null;
            }
            System.out.println("  🔁 Trying inference-header probe fallback (~9 real tokens, negligible vs quota)...");
            return probed;
        } catch (Exception e) {
            System.err.println("  ❌ " + id + " fallback probe failed - " + e.getMessage());
            return null;
        }
    }

    /**
     * Automatic (non-button) fallback for the periodic sweep. Allowed on the normal
     * "fallback is manual-only" rule's two exceptions, both because the per-call cost
     * (~9 tokens) is negligible against any plan's quota:
     * <ol>
     *   <li><b>Never-probed accounts</b> (app just started, or a credential was just added) —
     *       always allowed once, so a broken normal route doesn't leave the row blank until
     *       someone happens to click it.</li>
     *   <li><b>Recurring auto-refresh</b> — allowed again once its cooldown elapses: 5 minutes
     *       for the account Claude Code is currently active on ({@code claudecodeactive=true} —
     *       worth checking often since that's the account actually being burned through, and on
     *       a higher-tier plan a 9-token probe is even more negligible), 15 minutes for every
     *       other account.</li>
     * </ol>
     * Cooldown gating (and sharing one clock with the manual path) now lives on {@link
     * #probeSource} itself — see {@code OAuthUsageSource#fetchIfDue}.
     */
    private UsageData tryAutoProbeFallback(CCAPICredential credential) {
        if (probeSource == null) return null;
        var id = credential.id();
        var cooldownMs = credential.claudecodeactive()
                ? ccsuite.oauthswap.usage.UsageService.AUTO_ACTIVE_FALLBACK_COOLDOWN_MS
                : ccsuite.oauthswap.usage.UsageService.AUTO_OTHER_FALLBACK_COOLDOWN_MS;
        try {
            // Inside the cooldown the shared source still knows the watcher's latest reading; a key-less account lives on that.
            var probed = probeSource.fetchOrLastKnown(id, cooldownMs);
            if (probed == null) return null; // nothing read yet (or unavailable) - stay quiet, this path runs every sweep
            System.out.println("  🔁 " + id + ": auto fallback probe succeeded (~9 tokens)");
            return probed;
        } catch (Exception e) {
            System.err.println("  ⚠️  " + id + ": auto fallback probe failed - " + e.getMessage());
            return null;
        }
    }

    /**
     * Re-read CCAPIsCredentials.xml, keeping the previous in-memory copy if the reload fails
     * or yields no trackable accounts (e.g. a mid-edit read). claudeswap writes atomically,
     * so a normal external update is always seen whole.
     */
    private void reloadCredentialsBestEffort() {
        try {
            var fresh = credentialsManager.load();
            if (fresh != null && !fresh.getPaidUsageCredentials().isEmpty()) {
                credentials = fresh;
                markLiveActive();
            }
        } catch (Exception e) {
            System.err.println("⚠️  Could not reload credentials (keeping previous): " + e.getMessage());
        }
    }

    /** Puts the account Claude Code is really on first and marks it, when the host can tell (see {@link #hostedBy}). */
    private void markLiveActive() {
        if (liveActiveId == null) return;
        try {
            LiveActive.apply(credentials, liveActiveId.get());
        } catch (Exception e) {
            System.err.println("⚠️  Could not tell which account Claude Code is on (keeping the file's flag): " + e.getMessage());
        }
    }

    /** One beat of the active name in every row; only the active row glows, the others are put back to plain. */
    private void pulseRows() {
        if (!windowShown) return;
        rowControllers.forEach((id, row) -> {
            var c = credentials == null ? null : credentials.getById(id);
            row.pulse(c != null && c.claudecodeactive(), GlowPulse.danger(latestAccountUsages.get(id)));
        });
    }

    /** A switch shows at once: every 2 s the time of Claude Code's credentials file is read (a login or a switch rewrites it), and only when it moved is the active account looked up again. Hosted only. */
    private void startLiveFollow() {
        if (liveActiveId == null || liveTimer != null) return;
        var file = ccsuite.accounts.ClaudeCodePaths.defaults().credentialsFile();
        var seen = new long[]{mtime(file)};
        liveTimer = Executors.newSingleThreadScheduledExecutor(r -> Thread.ofPlatform().daemon().name("usage-live-active").unstarted(r));
        liveTimer.scheduleWithFixedDelay(() -> {
            var now = mtime(file);
            if (now == seen[0]) return;
            seen[0] = now;
            if (credentials != null) markLiveActive();
            pushTaskbar();
        }, 2, 2, TimeUnit.SECONDS);
    }

    static long mtime(java.nio.file.Path f) {
        try { return java.nio.file.Files.getLastModifiedTime(f).toMillis(); } catch (Exception e) { return 0; }
    }

    private void startGlow() {
        if (glowTimer != null || !uiSettings.glow()) return;
        glowTimer = Executors.newSingleThreadScheduledExecutor(r -> Thread.ofPlatform().daemon().name("usage-glow").unstarted(r));
        glowTimer.scheduleWithFixedDelay(() -> { if (windowShown) Platform.runLater(this::pulseRows); },
                GlowPulse.TICK_MS, GlowPulse.TICK_MS, TimeUnit.MILLISECONDS);
        primaryStage.showingProperty().addListener((o, was, now) -> windowShown = now);
        windowShown = primaryStage.isShowing();
    }

    private void updateUsageData() {
        // Reload the credentials file each sweep so an external change (e.g. claudeswap marking
        // a new claudecodeactive account) is picked up on the next cycle without a restart.
        reloadCredentialsBestEffort();

        var creds = credentials.getPaidUsageCredentialsOrdered();
        if (creds.isEmpty()) {
            System.err.println("❌ No credentials with trackUsage=true found");
            Platform.runLater(() -> showError("No credentials with trackUsage=true found"));
            return;
        }

        // (Re)build the rows only when the set of accounts changes. On a normal periodic
        // refresh the rows stay put and we just update values in place (no clear/flicker).
        var ids = creds.stream().map(CCAPICredential::id).toList();
        if (!ids.equals(lastRowIds)) {
            // Show every account at once from what is already known (this run's last reading, else the watcher's log), never as a blank:
            // reading an account is a real request, and the window has no need to wait for one to draw. Fresh data replaces it below.
            var placeholders = creds.stream().map(this::lastKnownOrBlank).toList();
            placeholders.forEach(p -> { if (p.usageData() != null) latestAccountUsages.putIfAbsent(p.credential().id(), p); });
            pushTaskbar();
            Platform.runLater(() -> displayUsageData(placeholders));
            lastRowIds = ids;
        }

        // Fetch only the accounts whose reading is not fresh, one at a time and spaced out, so neither a start nor the minute-by-minute sweep sends a burst of
        // requests (a burst can answer HTTP 500). Each row updates the moment its data arrives; a refresh the user asks for is never held back.
        var spacingMs = uiSettings.usageFetchSpacingMs();
        var due = creds.stream().filter(c -> !fresh(c)).toList();
        System.out.println("📊 Refreshing " + due.size() + " of " + creds.size() + " account(s) (the others are fresh; spacing=" + spacingMs + "ms)");
        for (int i = 0; i < due.size(); i++) {
            if (i > 0 && spacingMs > 0) sleepQuietly(spacingMs);
            fetchAndUpdate(due.get(i));
        }
    }

    static final long ACTIVE_FRESH_MS = 2 * 60_000L, OTHER_FRESH_MS = 5 * 60_000L;
    private final java.util.Map<String, Long> lastAttempt = new java.util.concurrent.ConcurrentHashMap<>();

    /** The account was tried, or its reading made, within the last two minutes (Claude Code's account) or five (any other): no new request for it yet. */
    private boolean fresh(CCAPICredential c) {
        var gate = c.claudecodeactive() ? ACTIVE_FRESH_MS : OTHER_FRESH_MS;
        var now = System.currentTimeMillis();
        var tried = lastAttempt.get(c.id());
        if (tried != null && now - tried < gate) return true;
        var last = latestAccountUsages.get(c.id());
        return last != null && last.usageData() != null && last.fetchedAt() != null && now - last.fetchedAt().toInstant().toEpochMilli() < gate;
    }

    /**
     * Fetch one account (with backoff retry) and push the result to its row in place.
     */
    private void fetchAndUpdate(CCAPICredential credential) {
        lastAttempt.put(credential.id(), System.currentTimeMillis());
        var accountUsage = fetchAccountUsage(credential);
        latestAccountUsages.put(credential.id(), accountUsage);
        pushTaskbar();
        logCoolDownInfo(accountUsage);
        var all = allNow();
        Platform.runLater(() -> {
            var controller = rowControllers.get(credential.id());
            if (controller != null) {
                controller.updateUsageData(accountUsage, uiSettings.showTime());
            }
            var tooltip = rowTooltips.get(credential.id());
            if (tooltip != null) {
                tooltip.setText(buildTooltipText(accountUsage));
            }
            showAll(all);
        });
    }

    /**
     * Fetch usage for a single credential. On failure (missing baseUrl, exhausted retries),
     * tries the automatic fallback (see {@link #tryAutoProbeFallback}); if that's unavailable
     * or on cooldown, returns the last successfully-fetched data for this account instead of
     * blanking the row — otherwise an account whose normal route persistently fails gets wiped
     * back to N/A on every periodic sweep.
     */
    private AccountUsage fetchAccountUsage(CCAPICredential credential) {
        // An account with no claude.ai session key (one added only by its Claude Code login) has nothing the cookie route
        // can use; go straight to the login's own route instead of failing and retrying the cookie one.
        if (credential.key() == null || credential.key().isBlank()) {
            var probed = tryAutoProbeFallback(credential);
            return probed != null ? new AccountUsage(credential, probed) : lastKnownOrBlank(credential);
        }
        var baseUrl = credential.resolvedCcapiBaseUrl();
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            System.err.println("  ⚠️  " + credential.id() + " skipped - ccapiBaseUrl not set");
            return lastKnownOrBlank(credential);
        }
        try {
            var usageData = apiClient.fetchUsageWithRetry(credential,
                    uiSettings.usageMaxRetries(), uiSettings.usageRetryBaseMs(), uiSettings.usageRetryMaxMs());
            return new AccountUsage(credential, usageData);
        } catch (Exception e) {
            System.err.println("  ❌ " + credential.id() + " failed - " + e.getMessage());
            var autoFallbackData = tryAutoProbeFallback(credential);
            return autoFallbackData != null ? new AccountUsage(credential, autoFallbackData) : lastKnownOrBlank(credential);
        }
    }

    private volatile java.util.Map<String, ccsuite.usagetracker.model.LastReadings.Reading> logged = java.util.Map.of();
    private volatile long loggedAt;

    /** The last reading of the account in the watcher's log (re-read every five minutes at most): an account that answers nothing is not dead, its log line holds when it comes back. */
    private ccsuite.usagetracker.model.LastReadings.Reading loggedReading(String id) {
        if (System.currentTimeMillis() - loggedAt > 5 * 60_000L) {
            logged = ccsuite.usagetracker.model.LastReadings.load(ccsuite.usagetracker.model.LastReadings.standard(), 3);
            loggedAt = System.currentTimeMillis();
        }
        return logged.get(id);
    }

    /**
     * Last successfully-fetched data for this account, or a blank AccountUsage if nothing
     * has ever been fetched. Used on fetch failure so a transient error — or an account whose
     * normal route persistently fails — doesn't erase the last known-good numbers.
     */
    private AccountUsage lastKnownOrBlank(CCAPICredential credential) {
        var previous = latestAccountUsages.get(credential.id());
        if (previous != null && previous.usageData() != null) return previous;
        var line = loggedReading(credential.id());
        return line != null ? ccsuite.usagetracker.model.LastReadings.page(credential, line, java.time.Instant.now()) : new AccountUsage(credential, null);
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void displayUsageData(List<AccountUsage> accountUsages) {
        installChrome();

        // Clear and populate accounts container
        accountsContainer.getChildren().clear();
        rowControllers.clear(); // rebuilt below; sweep updates rows through this registry
        latestAccountUsages.clear();
        rowTooltips.clear();

        // Account rows with separators
        for (int i = 0; i < accountUsages.size(); i++) {
            var accountUsage = accountUsages.get(i);
            var row = createAccountRow(accountUsage);
            accountsContainer.getChildren().add(row);

            // Add separator after each row except the last one
            if (i < accountUsages.size() - 1) {
                var separator = createSeparator();
                accountsContainer.getChildren().add(separator);
            }
        }
        var withAll = accountUsages.size() >= 2;
        if (withAll) {   // all accounts as one more row, the same as the card's ALL page
            accountsContainer.getChildren().add(createSeparator());
            accountsContainer.getChildren().add(createAllRow(allPage(accountUsages)));
        }

        // Only adjust window height if:
        // 1. First time displaying (lastAccountCount == -1), OR
        // 2. Account count has changed, AND
        // 3. User has not manually resized the window
        if (!userManuallyResized && (lastAccountCount == -1 || lastAccountCount != accountUsages.size())) {
            var visibleRows = Math.min(accountUsages.size() + (withAll ? 1 : 0), 6);
            var newHeight = TITLE_BAR_HEIGHT + (visibleRows * ROW_HEIGHT) + (visibleRows - 1) * 2 + 10; // +2 per separator
            newHeight = Math.max(currentMinHeight(), Math.min(MAX_HEIGHT, newHeight));

            // Mark as programmatic change to prevent save triggering
            programmaticHeightChange = true;
            primaryStage.setHeight(newHeight);
            programmaticHeightChange = false;
            if (placedByDefault) placeByDefault(primaryStage); else keepOnScreen(primaryStage);   // a window grown by a row never hangs below the taskbar

            lastAccountCount = accountUsages.size();
        }
    }

    /** The ALL page of the card (the averages of every account), or an empty ALL while there is nothing to average yet. */
    private AccountUsage allPage(List<AccountUsage> list) {
        var page = feed ? feedAll : ccsuite.usagetracker.taskbar.AllPage.page(ccsuite.usagetracker.taskbar.AllPage.total(list), list.size());
        return page != null ? page : new AccountUsage(new CCAPICredential().id("ALL").name("All accounts").active(true).tier(list.size()), null);
    }

    /** The ALL row: refreshing it sweeps every account. */
    private HBox createAllRow(AccountUsage all) {
        var row = createAccountRow(all);
        var controller = rowControllers.get("ALL");
        if (controller != null) controller.setOnRefresh(feed ? () -> rereadRow(all) : () -> { if (refreshExecutor != null) refreshExecutor.submit(this::safeUpdate); });
        return row;
    }

    /** The ALL page as of now, from the accounts read so far; null with fewer than two accounts shown. */
    private AccountUsage allNow() {
        var ids = lastRowIds;
        if (ids == null || ids.size() < 2) return null;
        return allPage(ids.stream().map(latestAccountUsages::get).filter(java.util.Objects::nonNull).toList());
    }

    /** FX thread: puts the ALL page in its row and its hover. */
    private void showAll(AccountUsage all) {
        if (all == null) return;
        var controller = rowControllers.get("ALL");
        if (controller != null) controller.updateUsageData(all, uiSettings.showTime());
        var tooltip = rowTooltips.get("ALL");
        if (tooltip != null) tooltip.setText(buildTooltipText(all));
    }

    /**
     * Create a thin colored separator line between account rows
     */
    private Region createSeparator() {
        var separator = new Region();
        separator.setPrefHeight(2);
        separator.setMinHeight(2);
        separator.setMaxHeight(2);
        separator.setStyle("-fx-background-color: linear-gradient(to right, transparent, " + palette.accent() + ", transparent);");
        return separator;
    }

    /**
     * Create account row using FXML-based UI.
     * This is the new implementation that uses the hand-made FXML.
     */
    private HBox createAccountRow(AccountUsage accountUsage) {
        try {
            // Load FXML
            var fxmlLoader = new FXMLLoader(
                getClass().getResource("/ccsuite/usagetracker/ui/usage-widget-row-v2.fxml")
            );
            HBox row = fxmlLoader.load();

            // Get controller
            UsageWidgetController controller = fxmlLoader.getController();

            // Set root container for background color changes
            controller.setRootContainer(row);
            controller.setPalette(palette);

            // Register controller so the periodic sweep can update this row in place
            rowControllers.put(accountUsage.credential().id(), controller);

            // Set up callback handlers
            controller.setOnRefresh(() -> {
                var credId = accountUsage.credential().id();
                var timestamp = java.time.LocalDateTime.now().format(
                    java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")
                );

                System.out.println("═══════════════════════════════════════════════════════════");
                System.out.println("🔄 REFRESH BUTTON CLICKED");
                System.out.println("  Account: " + credId);
                System.out.println("  Time: " + timestamp);
                System.out.println("  Action: Refreshing ONLY this account...");
                System.out.println("═══════════════════════════════════════════════════════════");

                // Set button to busy state (red, disabled)
                controller.setRefreshButtonBusy(true);

                try {
                    // Refresh only this specific account
                    refreshSingleAccount(accountUsage.credential(), row, controller);
                } finally {
                    // Restore button state (normal, enabled)
                    controller.setRefreshButtonBusy(false);
                }
            });

            if (feed) controller.setOnRefresh(() -> rereadRow(accountUsage));
            controller.setOnTypeClick(() -> rereadRow(accountUsage));
            controller.setOnNameClick(() -> { var h = activateHandler; var id = accountUsage.credential().id(); if (h != null && !feed && !"ALL".equals(id)) h.accept(id); });
            controller.setPingButtonVisible(false);

            // Update UI with data
            controller.updateUsageData(accountUsage, uiSettings.showTime());

            // Add hover tooltip with detailed status
            installDetailedTooltip(row, accountUsage);

            return row;
        } catch (Exception e) {
            System.err.println("Failed to load FXML for account row: " + e.getMessage());
            e.printStackTrace();
            // Fallback to deprecated programmatic UI
            return createAccountRowProgrammatic(accountUsage);
        }
    }

    /**
     * @deprecated Use {@link #createAccountRow(AccountUsage)} which uses FXML-based UI.
     * This method is kept for fallback purposes only.
     */
    @Deprecated
    private HBox createAccountRowProgrammatic(AccountUsage accountUsage) {
        // Single horizontal row per PRP requirements
        var row = new HBox(1);
        row.setPadding(new Insets(1));
        row.setStyle("-fx-background-color: rgba(50, 50, 50, 0.8); -fx-background-radius: 2;");
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPrefHeight(ROW_HEIGHT);
        row.setMinHeight(ROW_HEIGHT);
        row.setMaxHeight(ROW_HEIGHT);

        // 1. Account ID + Tier (stacked vertically) - FIXED WIDTH for alignment
        var idTierBox = new VBox(0);
        idTierBox.setAlignment(Pos.CENTER_LEFT);
        idTierBox.setPadding(new Insets(0, 1, 0, 2));
        // Fixed width based on idMaxLen to ensure grid alignment
        var idWidth = uiSettings.idMaxLen() * 7.5; // Width per character (monospace estimate)
        idTierBox.setMinWidth(idWidth);
        idTierBox.setPrefWidth(idWidth);
        idTierBox.setMaxWidth(idWidth);

        // Truncate ID based on settings
        var fullId = accountUsage.credential().id();
        var displayId = fullId.length() > uiSettings.idMaxLen()
            ? fullId.substring(0, uiSettings.idMaxLen())
            : fullId;

        var accountIdLabel = new Label(displayId);
        accountIdLabel.setStyle("-fx-text-fill: white; -fx-font-size: 7px; -fx-font-weight: bold;");

        // Show tier with better visibility
        var tier = accountUsage.credential().tier();
        var tierLabel = new Label(tier == 0 ? "F" : String.valueOf(tier)); // F for free tier
        tierLabel.setStyle("-fx-text-fill: #AAAAAA; -fx-font-size: 7px;");

        idTierBox.getChildren().addAll(accountIdLabel, tierLabel);

        // 2. Progress indicators VBox (usage on top, time below)
        var progressBox = new VBox(1);
        progressBox.setAlignment(Pos.CENTER);
        progressBox.setPadding(Insets.EMPTY);

        // Get color based on usage percentage
        var usagePercent = accountUsage.getFiveHourUsagePercent();
        var usageColor = getProgressColorFromPercent(usagePercent);

        // Usage progress (top)
        var usageProgress = new ProgressIndicatorComponent(
            usagePercent,
            100.0,
            usageColor
        );

        // Time progress (below) - always blue
        var timeProgress = new ProgressIndicatorComponent(
            accountUsage.getFiveHourTimeElapsedPercent(),
            100.0,
            "#00BFFF"
        );

        progressBox.getChildren().addAll(usageProgress, timeProgress);

        // 3. Metrics VBox (utilization rate on top, time info below)
        var metricsBox = new VBox(0);
        metricsBox.setAlignment(Pos.CENTER);
        metricsBox.setPadding(Insets.EMPTY);

        // Utilization Rate (usage/time as percentage)
        var utilizationRate = accountUsage.getUsageToTimeRatio() * 100.0;
        var rateLabel = new Label(String.format("%.0f%%", utilizationRate));
        var rateColor = getUtilizationColor(utilizationRate);
        rateLabel.setStyle(String.format(
            "-fx-text-fill: %s; -fx-font-size: 7px; -fx-font-weight: bold;",
            rateColor
        ));
        rateLabel.setAlignment(Pos.CENTER);

        // Time info based on settings (resetTime or timeRemaining)
        var timeText = "resetTime".equals(uiSettings.showTime())
            ? accountUsage.getCycleResetTime()
            : accountUsage.getTimeUntilReset();
        var timeLabel = new Label(timeText);
        timeLabel.setStyle("-fx-text-fill: #00BFFF; -fx-font-size: 7px;");
        timeLabel.setAlignment(Pos.CENTER);

        metricsBox.getChildren().addAll(rateLabel, timeLabel);

        row.getChildren().addAll(idTierBox, progressBox, metricsBox);

        // Add hover tooltip with detailed status
        installDetailedTooltip(row, accountUsage);

        return row;
    }

    /**
     * Installs a detailed tooltip on hover showing expanded usage statistics.
     *
     * @param node The node to attach the tooltip to
     * @param accountUsage Account usage data
     */
    private void installDetailedTooltip(HBox node, AccountUsage accountUsage) {
        var tooltip = new Tooltip();
        tooltip.setShowDelay(Duration.millis(1000));
        tooltip.setHideDelay(Duration.millis(100));
        tooltip.setStyle(UsageDetails.POPUP_STYLE);
        tooltip.setText(buildTooltipText(accountUsage));
        // Register so fetchAndUpdate can push fresh text eagerly on every data refresh
        rowTooltips.put(accountUsage.credential().id(), tooltip);
        Tooltip.install(node, tooltip);
    }

    private void logCoolDownInfo(AccountUsage accountUsage) {
        var usageData = accountUsage.usageData();
        if (usageData == null || usageData.sevenDay() == null) return;
        var sevenDay = usageData.sevenDay();
        var usage = sevenDay.utilization();
        var timeElapsed = calculateTimeElapsedPercent(sevenDay.resetsAt(), 7 * 24 * 60 * 60);
        var rate = timeElapsed > 0 ? (usage / timeElapsed) * 100.0 : 0.0;
        var coolDown = accountUsage.getWeeklyCoolDownFormatted();
        System.out.printf("[cool-down] %s | weekly usage=%.1f%% | time-elapsed=%.1f%% | rate=%.1f%% | cool-down=%s%n",
            accountUsage.credential().id(), usage, timeElapsed, rate,
            coolDown != null ? "~" + coolDown : "none (rate <=100%)");
    }

    private String buildTooltipText(AccountUsage accountUsage) {
        return UsageDetails.text(accountUsage);
    }

    /**
     * Calculate time elapsed percentage for any window duration
     *
     * @param resetTimeUtc Reset time in UTC
     * @param windowSeconds Total window duration in seconds
     * @return Time elapsed percentage (0-100)
     */
    private double calculateTimeElapsedPercent(java.time.OffsetDateTime resetTimeUtc, long windowSeconds) {
        if (resetTimeUtc == null) {
            return 0.0;
        }

        var now = java.time.OffsetDateTime.now();
        var timeUntilReset = java.time.Duration.between(now, resetTimeUtc).getSeconds();
        var elapsedSeconds = windowSeconds - timeUntilReset;

        return (elapsedSeconds * 100.0) / windowSeconds;
    }

    /**
     * Gets color based on usage percentage.
     *
     * @param percentage Usage percentage (0-100)
     * @return CSS color string
     */
    private String getProgressColorFromPercent(double percentage) {
        if (percentage >= 80) return "#FF4444"; // Red
        if (percentage >= 60) return "#FFAA00"; // Orange
        return "#44FF44"; // Green
    }

    /**
     * Gets color based on utilization rate percentage.
     * Utilization rate = (usage / time elapsed) * 100
     *
     * @param rate Utilization rate percentage
     * @return CSS color string
     */
    private String getUtilizationColor(double rate) {
        if (rate >= 120) return "#FF4444"; // Red - using too fast
        if (rate >= 100) return "#FFAA00"; // Orange - right at the edge
        return "#44FF44"; // Green - using slower than time passing
    }

    private void showError(String message) {
        // Handle errors that occur before UI is initialized
        if (contentBox == null) {
            System.err.println("Error: " + message);
            // Show error dialog for early initialization errors
            Platform.runLater(() -> {
                javafx.scene.control.Alert alert = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.ERROR);
                alert.setTitle("Usage Tracker Error");
                alert.setHeaderText(null);
                alert.setContentText(message);
                alert.showAndWait();
                Platform.exit();
            });
            return;
        }

        Platform.runLater(() -> {
            contentBox.getChildren().clear();
            var errorLabel = new Label("Error: " + message);
            errorLabel.setStyle("-fx-text-fill: red; -fx-font-size: 12px;");
            contentBox.getChildren().add(errorLabel);
        });
    }

    /**
     * Makes the header both draggable (for moving window) and resizable from top edge.
     * Detects if mouse is in resize zone (top 5px), otherwise allows dragging.
     */
    private void makeHeaderInteractive(Stage stage, Region headerNode) {
        final double RESIZE_MARGIN = 5; // Pixels from top edge to trigger resize
        final double[] dragXOffset = {0};
        final double[] dragYOffset = {0};
        final double[] resizeStartY = {0};
        final double[] resizeStartHeight = {0};
        final double[] resizeStartStageY = {0};
        final boolean[] isResizing = {false};
        final boolean[] isDragging = {false};

        headerNode.setOnMouseMoved(event -> {
            // Use screen coordinates to detect if mouse is near top of window
            var mouseScreenY = event.getScreenY();
            var windowTop = stage.getY();

            // Change cursor when near top edge
            if (mouseScreenY <= windowTop + RESIZE_MARGIN) {
                headerNode.setCursor(javafx.scene.Cursor.N_RESIZE);
            } else {
                if (!isResizing[0] && !isDragging[0]) {
                    headerNode.setCursor(javafx.scene.Cursor.DEFAULT);
                }
            }
        });

        headerNode.setOnMousePressed(event -> {
            // Use screen coordinates to detect if clicked near top of window
            var mouseScreenY = event.getScreenY();
            var windowTop = stage.getY();

            // Start resize if clicked near top edge
            if (mouseScreenY <= windowTop + RESIZE_MARGIN) {
                isResizing[0] = true;
                resizeStartY[0] = event.getScreenY();
                resizeStartHeight[0] = stage.getHeight();
                resizeStartStageY[0] = stage.getY();
                event.consume();
            } else if (mouseScreenY < stage.getY() + stage.getHeight() - 8) {
                // Start drag if NOT in the top or bottom resize zones
                isDragging[0] = true;
                dragXOffset[0] = event.getSceneX();
                dragYOffset[0] = event.getSceneY();
            }
            // else: near the bottom edge → leave it to enableResizeBottom
        });

        headerNode.setOnMouseDragged(event -> {
            if (isResizing[0]) {
                // Handle resize
                var deltaY = event.getScreenY() - resizeStartY[0];
                var newHeight = resizeStartHeight[0] - deltaY; // Subtract because dragging up
                var newY = resizeStartStageY[0] + deltaY;

                // Clamp to min/max height
                if (newHeight >= currentMinHeight() && newHeight <= MAX_HEIGHT) {
                    stage.setHeight(newHeight);
                    stage.setY(newY);
                }
                event.consume();
            } else if (isDragging[0]) {
                // Handle drag (move window)
                stage.setX(event.getScreenX() - dragXOffset[0]);
                stage.setY(event.getScreenY() - dragYOffset[0]);
            }
        });

        headerNode.setOnMouseReleased(event -> {
            if (isResizing[0] || isDragging[0]) {
                if (isResizing[0]) {
                    userManuallyResized = true; // Mark that user manually resized
                }
                isResizing[0] = false;
                isDragging[0] = false;
                headerNode.setCursor(javafx.scene.Cursor.DEFAULT);
                saveWindowPosition(); // Save new position/height
            }
        });

        headerNode.setOnMouseExited(event -> {
            if (!isResizing[0] && !isDragging[0]) {
                headerNode.setCursor(javafx.scene.Cursor.DEFAULT);
            }
        });
    }

    private void makeDraggable(Stage stage, HBox node) {
        final double[] xOffset = {0};
        final double[] yOffset = {0};

        node.setOnMousePressed(event -> {
            xOffset[0] = event.getSceneX();
            yOffset[0] = event.getSceneY();
        });

        node.setOnMouseDragged(event -> {
            stage.setX(event.getScreenX() - xOffset[0]);
            stage.setY(event.getScreenY() - yOffset[0]);
        });

        // Save position when drag ends
        node.setOnMouseReleased(event -> {
            saveWindowPosition();
        });
    }

    /**
     * Set window position and height from settings (lastScreenLoc)
     * Format: "x,y" or "x,y,height"
     * Validates position is within current screen bounds
     */
    private void setWindowPosition(Stage stage) {
        System.out.println("📍 LOADING window position from settings: " + uiSettings.lastScreenLoc());
        if (uiSettings.lastScreenLoc() == null || uiSettings.lastScreenLoc().isEmpty()) { placeByDefault(stage); return; }
        if (uiSettings.lastScreenLoc() != null && !uiSettings.lastScreenLoc().isEmpty()) {
            try {
                var parts = uiSettings.lastScreenLoc().split(",");
                if (parts.length >= 2) {
                    var x = Double.parseDouble(parts[0].trim());
                    var y = Double.parseDouble(parts[1].trim());
                    var height = parts.length >= 3 ? Double.parseDouble(parts[2].trim()) : MIN_HEIGHT;
                    System.out.println("   Parsed: x=" + x + ", y=" + y + ", height=" + height);

                    // Clamp height to valid range
                    height = Math.max(currentMinHeight(), Math.min(MAX_HEIGHT, height));

                    // Get screen bounds
                    var screen = javafx.stage.Screen.getPrimary();
                    var bounds = screen.getVisualBounds();

                    // Validate position has positive projection on ANY screen
                    // Simple overlap check - if window overlaps screen at all, it's valid
                    // This handles taskbars, multi-monitor setups, and negative coordinates

                    System.out.println("   Primary screen bounds: minX=" + bounds.getMinX() + ", maxX=" + bounds.getMaxX() +
                                     ", minY=" + bounds.getMinY() + ", maxY=" + bounds.getMaxY());

                    // Check all screens (multi-monitor setup)
                    var screens = javafx.stage.Screen.getScreens();
                    System.out.println("   🖥️  Detected " + screens.size() + " screen(s)");
                    var isVisibleOnAnyScreen = false;

                    for (int i = 0; i < screens.size(); i++) {
                        var scr = screens.get(i);

                        // Use FULL screen bounds (includes taskbar) instead of visual bounds (excludes taskbar)
                        var screenBounds = scr.getBounds();
                        var visualBounds = scr.getVisualBounds();

                        System.out.println("   🖥️  Screen #" + (i+1) + ":");
                        System.out.println("      Full bounds: minX=" + screenBounds.getMinX() + ", maxX=" + screenBounds.getMaxX());
                        System.out.println("      Visual bounds: minX=" + visualBounds.getMinX() + " (taskbar excludes " +
                                         (visualBounds.getMinX() - screenBounds.getMinX()) + "px)");

                        // Check for ANY overlap using rectangle intersection logic
                        // Window overlaps if: windowRight > screenLeft AND windowLeft < screenRight
                        //                AND: windowBottom > screenTop AND windowTop < screenBottom
                        var windowRight = x + WIDGET_WIDTH;
                        var windowBottom = y + height;

                        var hasOverlap = (windowRight > screenBounds.getMinX()) &&     // Window extends into screen from left
                                       (x < screenBounds.getMaxX()) &&               // Window starts before screen ends
                                       (windowBottom > screenBounds.getMinY()) &&    // Window extends into screen from top
                                       (y < screenBounds.getMaxY());                 // Window starts before screen ends

                        System.out.println("      → Window rect: [" + x + "," + y + " to " + windowRight + "," + windowBottom + "]");
                        System.out.println("      → Overlap detected: " + hasOverlap);

                        if (hasOverlap) {
                            isVisibleOnAnyScreen = true;
                            System.out.println("      ✅ VALID! Window has positive projection on screen");
                            break;
                        }
                    }

                    if (!isVisibleOnAnyScreen) {
                        // Position is invalid, use center of primary screen
                        x = bounds.getMinX() + (bounds.getWidth() - WIDGET_WIDTH) / 2;
                        y = bounds.getMinY() + (bounds.getHeight() - height) / 2;
                        System.out.println("   ⚠️  Position not visible on any screen! Resetting to center: x=" + x + ", y=" + y);

                        // Save the corrected position
                        uiSettings.lastScreenLoc(String.format("%.0f,%.0f,%.0f", x, y, height));
                        saveSettings();
                    }

                    programmaticHeightChange = true;
                    System.out.println("   🎯 Setting stage position: x=" + x + ", y=" + y + ", height=" + height);
                    stage.setX(x);
                    stage.setY(y);
                    stage.setHeight(height);
                    programmaticHeightChange = false;

                    // IMPORTANT: Mark that we loaded from settings so displayUsageData won't override
                    // We set lastAccountCount to a non-negative value to prevent auto-resize
                    // The actual account count will be set when displayUsageData is called
                    if (parts.length >= 3) {
                        // User has a saved height preference - don't auto-resize
                        userManuallyResized = true;
                        System.out.println("Loaded saved window position: x=" + x + ", y=" + y + ", height=" + height);
                    }
                }
            } catch (Exception e) {
                System.err.println("Failed to parse lastScreenLoc: " + e.getMessage());
            }
        }
    }

    /** The window moved back inside the usable part of the screen it is on (see {@link WidgetPlace#fit}). */
    private void keepOnScreen(Stage s) {
        if (!s.isShowing()) return;
        var b = javafx.stage.Screen.getScreensForRectangle(s.getX(), s.getY(), s.getWidth(), s.getHeight()).stream().findFirst().orElse(javafx.stage.Screen.getPrimary()).getVisualBounds();
        var at = WidgetPlace.fit(b.getMinX(), b.getMinY(), b.getMaxX(), b.getMaxY(), s.getX(), s.getY(), s.getWidth(), s.getHeight());
        s.setX(at[0]);
        s.setY(at[1]);
    }

    /** Nothing remembered yet: bottom right of the usable screen (see {@link WidgetPlace}), kept there while the window changes size, until the user moves it. */
    private void placeByDefault(Stage stage) {
        var b = javafx.stage.Screen.getPrimary().getVisualBounds();
        var width = sideBarMode ? WIDGET_WIDTH + LEFT_BAR_WIDTH : WIDGET_WIDTH;
        var height = Double.isNaN(stage.getHeight()) || stage.getHeight() < currentMinHeight() ? currentMinHeight() : stage.getHeight();
        var at = WidgetPlace.defaultAt(b.getMinX(), b.getMinY(), b.getMaxX(), b.getMaxY(), width, height, feed ? 1 : 0);
        stage.setX(at[0]);
        stage.setY(at[1]);
        placedByDefault = true;
    }

    /**
     * Save current window position and height to settings
     * Format: "x,y,height"
     */
    private void saveWindowPosition() {
        if (primaryStage != null) {
            var x = primaryStage.getX();
            var y = primaryStage.getY();
            var height = primaryStage.getHeight();
            var posString = String.format("%.0f,%.0f,%.0f", x, y, height);
            placedByDefault = false;
            System.out.println("💾 SAVING window position: " + posString);
            uiSettings.lastScreenLoc(posString);
            saveSettings();
        }
    }

    /**
     * Save settings to file
     */
    private void saveSettings() {
        try {
            settingsManager.save(settings);
        } catch (Exception e) {
            System.err.println("Failed to save settings: " + e.getMessage());
        }
    }

    /**
     * Open CCAPIsUsageTrackerSettings.xml in system text editor (cross-platform)
     */
    private void openSettingsInNotepad() {
        try {
            if (Desktop.isDesktopSupported()) {
                var desktop = Desktop.getDesktop();
                var file = settingsManager.getSettingsPath().toFile();

                if (desktop.isSupported(Desktop.Action.EDIT)) {
                    desktop.edit(file);
                } else if (desktop.isSupported(Desktop.Action.OPEN)) {
                    // Fallback to opening with default app if EDIT not supported
                    desktop.open(file);
                } else {
                    System.err.println("Neither EDIT nor OPEN action supported on this platform");
                }
            } else {
                System.err.println("Desktop not supported on this platform");
            }
        } catch (Exception e) {
            System.err.println("Failed to open settings file: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Enable custom vertical resize from bottom edge for transparent window
     * Detects mouse at bottom edge and allows dragging to resize downward
     */
    private void enableResizeBottom(Stage stage, VBox rootNode) {
        final double RESIZE_MARGIN = 8; // Pixels from bottom edge to detect resize
        final double[] resizeStartY = {0};
        final double[] resizeStartHeight = {0};
        final boolean[] isResizing = {false};

        rootNode.setOnMouseMoved(event -> {
            // Use screen coordinates to detect if mouse is near bottom of window
            var mouseScreenY = event.getScreenY();
            var windowBottom = stage.getY() + stage.getHeight();

            // Change cursor when near bottom edge
            if (mouseScreenY >= windowBottom - RESIZE_MARGIN) {
                rootNode.setCursor(javafx.scene.Cursor.S_RESIZE);
            } else {
                if (!isResizing[0]) {
                    rootNode.setCursor(javafx.scene.Cursor.DEFAULT);
                }
            }
        });

        rootNode.setOnMousePressed(event -> {
            // Use screen coordinates to detect if clicked near bottom of window
            var mouseScreenY = event.getScreenY();
            var windowBottom = stage.getY() + stage.getHeight();

            // Start resize if clicked near bottom edge
            if (mouseScreenY >= windowBottom - RESIZE_MARGIN) {
                isResizing[0] = true;
                resizeStartY[0] = event.getScreenY();
                resizeStartHeight[0] = stage.getHeight();
                event.consume();
            }
        });

        rootNode.setOnMouseDragged(event -> {
            if (isResizing[0]) {
                var deltaY = event.getScreenY() - resizeStartY[0];
                var newHeight = resizeStartHeight[0] + deltaY;

                // Clamp to min/max height
                newHeight = Math.max(currentMinHeight(), Math.min(MAX_HEIGHT, newHeight));

                stage.setHeight(newHeight);
                event.consume();
            }
        });

        rootNode.setOnMouseReleased(event -> {
            if (isResizing[0]) {
                userManuallyResized = true; // Mark that user manually resized
                isResizing[0] = false;
                rootNode.setCursor(javafx.scene.Cursor.DEFAULT);
                saveWindowPosition(); // Save new height
                event.consume();
            }
        });

        rootNode.setOnMouseExited(event -> {
            if (!isResizing[0]) {
                rootNode.setCursor(javafx.scene.Cursor.DEFAULT);
            }
        });
    }


    public static void main(String[] args) {
        launch(args);
    }
}
