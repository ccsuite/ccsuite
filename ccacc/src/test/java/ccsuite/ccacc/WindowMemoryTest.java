package ccsuite.ccacc;

import module java.base;
import javafx.geometry.Rectangle2D;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

/** The main window comes back where and as big as it was, on the tab it was left on, and never off the screen. */
class WindowMemoryTest {

    @Test void whatWasSavedIsReadBack(@TempDir Path dir) {
        var file = dir.resolve("window.json");
        WindowMemory.save(file, new WindowMemory.Saved(120, 80, 1000, 700, 3));
        var s = WindowMemory.load(file).orElseThrow();
        assertEquals(120, s.x());
        assertEquals(700, s.height());
        assertEquals(3, s.tab());
    }

    @Test void aMissingOrBrokenFileGivesNothingAndNoFileMeansNoMemory(@TempDir Path dir) throws Exception {
        assertTrue(WindowMemory.load(dir.resolve("none.json")).isEmpty());
        Files.writeString(dir.resolve("bad.json"), "{ not json");
        assertTrue(WindowMemory.load(dir.resolve("bad.json")).isEmpty());
        assertTrue(WindowMemory.load(null).isEmpty(), "no file given: no memory");
        WindowMemory.save(null, new WindowMemory.Saved(1, 1, 800, 600, 0));
    }

    @Test void aPositionOnAScreenIsKeptAndOneOffEveryScreenIsNot() {
        var screens = List.of(new Rectangle2D(0, 0, 1536, 864));
        assertTrue(WindowMemory.onScreen(new WindowMemory.Saved(100, 100, 900, 600, 0), screens));
        assertFalse(WindowMemory.onScreen(new WindowMemory.Saved(4000, 100, 900, 600, 0), screens), "a monitor that is gone");
        assertFalse(WindowMemory.onScreen(new WindowMemory.Saved(-2000, -2000, 900, 600, 0), screens));
        assertTrue(WindowMemory.onScreen(new WindowMemory.Saved(1400, 800, 900, 600, 0), screens), "partly on the screen still has something to grab");
    }

    @Test void theSizeIsNeverSmallerThanWhatTheWindowNeeds() {
        var s = WindowMemory.clamp(new WindowMemory.Saved(0, 0, 50, 40, 99), 6);
        assertEquals(600, s.width());
        assertEquals(400, s.height());
        assertEquals(0, s.tab(), "a tab that is not there any more: the first");
    }

    @Test void aWindowBiggerThanWhatIsLeftOfTheScreenIsMovedAndShrunkSoAllOfItShows() {
        var usable = new Rectangle2D(120, 0, 1109, 691);   // the screen minus a taskbar on the left
        var moved = WindowMemory.fit(new WindowMemory.Saved(369, 63, 920, 640, 0), usable);
        assertEquals(920, moved.width());
        assertEquals(120 + 1109 - 920, moved.x(), "pushed left until its right edge is on the screen");
        assertEquals(51, moved.y(), "and up until its bottom edge is");
        var shrunk = WindowMemory.fit(new WindowMemory.Saved(0, 0, 1500, 900, 0), usable);
        assertEquals(1109, shrunk.width());
        assertEquals(691, shrunk.height());
        assertEquals(120, shrunk.x());
        assertEquals(0, shrunk.y());
        var fine = WindowMemory.fit(new WindowMemory.Saved(200, 50, 900, 600, 2), usable);
        assertEquals(200, fine.x());
        assertEquals(50, fine.y());
        assertEquals(2, fine.tab(), "the tab is carried through");
    }
}
