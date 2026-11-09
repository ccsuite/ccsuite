package ccsuite.ccacc;

import module java.base;
import javafx.geometry.Rectangle2D;

/** Where the main window was left: position, size and the tab in front, in one small file. A broken or missing file means no memory; a position off every screen is not used. */
final class WindowMemory {

    /** Where the window was, how big, and which tab (by place in the tab strip). */
    record Saved(double x, double y, double width, double height, int tab) { }

    static final double MIN_WIDTH = 600, MIN_HEIGHT = 400;

    static Optional<Saved> load(Path file) {
        if (file == null || !Files.exists(file)) return Optional.empty();
        try {
            var j = CcaccSettingsStore.JSON.readTree(file.toFile());
            return Optional.of(new Saved(j.path("x").asDouble(0), j.path("y").asDouble(0), j.path("width").asDouble(0), j.path("height").asDouble(0), j.path("tab").asInt(0)));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    static void save(Path file, Saved s) {
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, CcaccSettingsStore.JSON.createObjectNode().put("x", s.x()).put("y", s.y()).put("width", s.width()).put("height", s.height()).put("tab", s.tab()).toString());
        } catch (IOException e) {
            System.err.println("ccacc: window position not saved: " + e.getMessage());
        }
    }

    /** Some part of the window is on some screen, so there is something to grab. */
    static boolean onScreen(Saved s, List<Rectangle2D> screens) {
        var window = new Rectangle2D(s.x(), s.y(), s.width(), s.height());
        return screens.stream().anyMatch(r -> r.intersects(window));
    }

    /** The window moved and shrunk until all of it is on the usable part of the screen (the screen minus the taskbar). */
    static Saved fit(Saved s, Rectangle2D usable) {
        var w = Math.min(s.width(), usable.getWidth());
        var h = Math.min(s.height(), usable.getHeight());
        return new Saved(Math.max(usable.getMinX(), Math.min(s.x(), usable.getMaxX() - w)), Math.max(usable.getMinY(), Math.min(s.y(), usable.getMaxY() - h)), w, h, s.tab());
    }

    /** At least the size the window needs, and a tab that exists. */
    static Saved clamp(Saved s, int tabs) {
        return new Saved(s.x(), s.y(), Math.max(MIN_WIDTH, s.width()), Math.max(MIN_HEIGHT, s.height()), s.tab() >= 0 && s.tab() < tabs ? s.tab() : 0);
    }

    private WindowMemory() {}
}
