package ccsuite.ccacc;

import module java.base;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.input.KeyCode;
import javafx.scene.control.Button;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/**
 * The one window of ccacc, in tabs: Paid Accounts (usage, switching, the watcher's mode), Free Accounts, Lanes (the switch and the log), Clients (who calls the gate),
 * Servers (every part as a switch) and Manage (the account editor, paid and free). It opens at once: the window shows first and the tab in front is filled right
 * after, a tab that was never in front is never built, and a tab not in front costs nothing ({@link Page}). A resident app must not keep JavaFX's frame clock
 * running, so a tab refreshes on a timer only while it is in front of a shown window. The tray menu stays for the quick things; Esc closes.
 */
final class MainWindow {

    final Parts parts;
    final WatchBridge bridge;
    final Supplier<String> statusText;
    final Stage stage = new Stage();
    final Label status = new Label();
    final PaidPane paid;
    final FreePane free;
    final LanesPane lanes;
    final ClientsPane clients;
    final ServersPane servers;
    final AccountsPane manage;
    final TabPane tabs = new TabPane();
    /** The keys of the tab in front, along the bottom: each is a button that does what its key does. */
    final HBox legend = new HBox(8);
    final Path memory;
    final Map<Tab, Page> pages = new LinkedHashMap<>();
    final Tab paidTab, freeTab, lanesTab, clientsTab, serversTab, manageTab;
    /** Show or hide the floating usage windows; they show by themselves unless the taskbar card is on (PRP 93); set by the app. */
    Runnable openPaidDialog = () -> {}, openFreeDialog = () -> {};

    MainWindow(Parts parts, WatchBridge bridge, String color, Supplier<String> statusText, FreePane free, TrayItems items) {
        this(parts, bridge, color, statusText, free, items, null);
    }

    /** {@code memory} is the file where the window keeps its position, size and tab (null: none). */
    MainWindow(Parts parts, WatchBridge bridge, String color, Supplier<String> statusText, FreePane free, TrayItems items, Path memory) {
        this.memory = memory;
        this.parts = parts;
        this.bridge = bridge;
        this.statusText = statusText;
        this.free = free;
        paid = new PaidPane(parts, bridge);
        lanes = new LanesPane(parts);
        clients = new ClientsPane(parts);
        servers = new ServersPane(parts, items);
        manage = new AccountsPane(parts, parts.core == null ? null : new Accounts(parts.core.credentials, parts.core.creds, this::accountsChanged));
        paid.onDialog = () -> openPaidDialog.run();
        free.onDialog = () -> openFreeDialog.run();
        paidTab = tab("Paid Accounts", paid);
        freeTab = tab("Free Accounts", free);
        lanesTab = tab("Lanes", lanes);
        clientsTab = tab("Clients", clients);
        serversTab = tab("Servers", servers);
        manageTab = tab("Manage", manage);

        var title = new Label("ccacc");
        title.setStyle("-fx-font-size: 20; -fx-font-weight: bold; -fx-text-fill: white;");
        status.setStyle("-fx-text-fill: white;");
        var band = new VBox(2, title, status);
        band.setPadding(new Insets(10, 14, 10, 14));
        band.setStyle("-fx-background-color: " + color + ";");
        legend.setPadding(new Insets(5, 14, 5, 14));
        legend.setStyle("-fx-background-color: #eeeeee; -fx-border-color: #cccccc transparent transparent transparent;");
        var scene = new Scene(new VBox(band, tabs, legend), 920, 640);
        VBox.setVgrow(tabs, Priority.ALWAYS);
        scene.setOnKeyPressed(e -> { if (e.getCode() == KeyCode.ESCAPE) stage.hide(); });
        scene.addEventFilter(KeyEvent.KEY_PRESSED, this::key);
        tabs.getSelectionModel().selectedItemProperty().addListener((o, was, now) -> sync());
        stage.setOnShown(e -> Platform.runLater(this::sync));
        stage.setOnHidden(e -> { remember(); sync(); });
        stage.setTitle("ccacc");
        stage.getIcons().setAll(FxImages.windowIcons());
        stage.setScene(scene);
        recall();
    }

    Tab tab(String text, Node content) {
        var t = new Tab(text, content);
        t.setClosable(false);
        pages.put(t, (Page) content);
        tabs.getTabs().add(t);
        return t;
    }

    /** Writes down where the window is, how big, and the tab in front. */
    void remember() {
        WindowMemory.save(memory, new WindowMemory.Saved(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight(), tabs.getSelectionModel().getSelectedIndex()));
    }

    /** Puts the window back as it was left: the size and tab always, the position when some part of it is on a screen. */
    void recall() {
        WindowMemory.load(memory).map(s -> WindowMemory.clamp(s, tabs.getTabs().size())).ifPresent(s -> {
            stage.setWidth(s.width());
            stage.setHeight(s.height());
            if (WindowMemory.onScreen(s, javafx.stage.Screen.getScreens().stream().map(javafx.stage.Screen::getVisualBounds).toList())) { stage.setX(s.x()); stage.setY(s.y()); }
            tabs.getSelectionModel().select(s.tab());
        });
    }

    /** The actions of the tab in front, then Esc: one list for the key handler and the legend. */
    List<KeyAction> frontKeys() {
        var page = pages.get(tabs.getSelectionModel().getSelectedItem());
        var all = new ArrayList<KeyAction>(page == null ? List.of() : page.keys());
        all.add(new KeyAction(KeyCode.ESCAPE, "Close", stage::hide));
        return all;
    }

    /** A function key with no modifier runs the front tab's action of that key, wherever the focus is (they are not typing). */
    void key(KeyEvent e) {
        if (e.isShiftDown() || e.isControlDown() || e.isAltDown() || e.isMetaDown() || e.getCode() == KeyCode.ESCAPE) return;
        frontKeys().stream().filter(k -> k.key() == e.getCode()).findFirst().ifPresent(k -> { e.consume(); k.run().run(); });
    }

    void legend() {
        legend.getChildren().setAll(frontKeys().stream().map(k -> {
            var b = new Button(k.text());
            b.setMnemonicParsing(false);
            b.setFocusTraversable(false);
            b.setOnAction(e -> k.run().run());
            return (javafx.scene.Node) b;
        }).toList());
    }

    /** Starts the tab in front of a shown window and stops every other; the first start of a tab is what builds its content. */
    void sync() {
        var front = tabs.getSelectionModel().getSelectedItem();   // not Tab.isSelected(): the listener runs before that flag is updated
        pages.forEach((t, p) -> { if (stage.isShowing() && t == front) p.start(); else p.stop(); });
        legend();
        if (stage.isShowing()) status.setText(statusText.get());
    }

    static final double DEFAULT_WIDTH = 940, DEFAULT_HEIGHT = 680;

    /** Before the window first shows: a place and a size of its own, wholly on the usable screen, so the system does not choose (it can put the window half off the screen). */
    void place() {
        var usable = javafx.stage.Screen.getPrimary().getVisualBounds();
        var w = Double.isNaN(stage.getWidth()) ? DEFAULT_WIDTH : stage.getWidth();
        var h = Double.isNaN(stage.getHeight()) ? DEFAULT_HEIGHT : stage.getHeight();
        var x = Double.isNaN(stage.getX()) ? usable.getMinX() + (usable.getWidth() - Math.min(w, usable.getWidth())) / 2 : stage.getX();
        var y = Double.isNaN(stage.getY()) ? usable.getMinY() + (usable.getHeight() - Math.min(h, usable.getHeight())) / 2 : stage.getY();
        var f = WindowMemory.fit(new WindowMemory.Saved(x, y, w, h, 0), usable);
        stage.setX(f.x());
        stage.setY(f.y());
        stage.setWidth(f.width());
        stage.setHeight(f.height());
    }

    /** Where the window is and the usable screen, for the command and for finding out why a window sits where it does. */
    String geometry() {
        var u = javafx.stage.Screen.getPrimary().getVisualBounds();
        return "at %.0f,%.0f size %.0fx%.0f on a screen usable from %.0f,%.0f size %.0fx%.0f (scale %.2f)".formatted(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight(),
                u.getMinX(), u.getMinY(), u.getWidth(), u.getHeight(), javafx.stage.Screen.getPrimary().getOutputScaleX());
    }

    /** All of the window on the usable part of its screen: a window placed by default or remembered can overhang an edge or a taskbar. */
    void fit() {
        if (Double.isNaN(stage.getWidth()) || Double.isNaN(stage.getHeight())) return;
        var screens = javafx.stage.Screen.getScreensForRectangle(stage.getX(), stage.getY(), Math.max(1, stage.getWidth()), Math.max(1, stage.getHeight()));
        var usable = (screens.isEmpty() ? javafx.stage.Screen.getPrimary() : screens.get(0)).getVisualBounds();
        var f = WindowMemory.fit(new WindowMemory.Saved(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight(), 0), usable);
        stage.setX(f.x());
        stage.setY(f.y());
        stage.setWidth(f.width());
        stage.setHeight(f.height());
    }

    void toggle() {
        if (stage.isShowing()) stage.hide(); else show();
    }

    void show() {
        IdleTrim.touch();
        if (!stage.isShowing()) place();
        stage.show();
        Platform.runLater(this::fit);
        stage.toFront();
    }

    /** The window with this tab in front. */
    void show(Tab tab) {
        tabs.getSelectionModel().select(tab);
        show();
    }

    /** The tab a command-line name means (paid, free, lanes, clients, servers, manage), the first tab for no name, null for an unknown one. */
    Tab tabNamed(String name) {
        if (name == null || name.isBlank()) return paidTab;
        return switch (name.toLowerCase()) {
            case "paid" -> paidTab;
            case "free" -> freeTab;
            case "lanes" -> lanesTab;
            case "clients" -> clientsTab;
            case "servers" -> serversTab;
            case "manage" -> manageTab;
            default -> null;
        };
    }

    void showFree() { show(freeTab); }

    void showLanes() { show(lanesTab); }

    void showServers() { show(serversTab); }

    /** An account was added, removed or changed: poll again so the numbers follow, and redraw. */
    void accountsChanged() {
        if (parts.watcher != null) parts.watcher.pollNow();
        redrawIfShowing();
    }

    void redrawIfShowing() {
        if (stage.isShowing()) redraw();
    }

    /** What the parts say now, into the tab in front (the others are stopped and ignore it). */
    void redraw() {
        status.setText(statusText.get());
        paid.redraw();
        free.redraw();
        servers.redraw();
        lanes.redraw();
    }
}
