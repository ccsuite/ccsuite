package ccsuite.ccacc;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

class CcaccIconTest {

    static final int[] SIZES = {16, 20, 24, 32, 48, 64, 96, 128, 256};

    @Test void everySizeGivesAnImageOfThatSize() {
        for (var size : SIZES) {
            var image = CcaccIcon.draw(size);
            assertEquals(size, image.getWidth());
            assertEquals(size, image.getHeight());
        }
    }

    @Test void everySizeIsNotBlank() {
        for (var size : SIZES) assertTrue(opaquePixels(CcaccIcon.draw(size)) > size, "size " + size);
    }

    @Test void cornersAreTransparent() {
        for (var size : SIZES) {
            var image = CcaccIcon.draw(size);
            var last = size - 1;
            for (var xy : new int[][]{{0, 0}, {last, 0}, {0, last}, {last, last}}) {
                assertTrue(alpha(image, xy[0], xy[1]) < 0x60, "size " + size + " corner " + Arrays.toString(xy));
            }
        }
    }

    @Test void twoCallsGiveTheSamePixels() {
        for (var size : SIZES) {
            assertArrayEquals(pixels(CcaccIcon.draw(size)), pixels(CcaccIcon.draw(size)));
            assertArrayEquals(pixels(CcaccIcon.drawWithMode(size, true)), pixels(CcaccIcon.drawWithMode(size, true)));
        }
    }

    @Test void modeMarkChangesThePictureAndAutoDiffersFromOff() {
        var plain = pixels(CcaccIcon.draw(32));
        var auto = pixels(CcaccIcon.drawWithMode(32, true));
        var off = pixels(CcaccIcon.drawWithMode(32, false));
        assertFalse(Arrays.equals(plain, auto));
        assertFalse(Arrays.equals(auto, off));
    }

    @Test void everyConceptDrawsAtEverySize() {
        for (var concept : "ABC".toCharArray()) {
            for (var size : SIZES) assertTrue(opaquePixels(CcaccIcon.of(concept, size, null)) > size, concept + " " + size);
        }
    }

    static int alpha(BufferedImage image, int x, int y) {
        return image.getRGB(x, y) >>> 24;
    }

    static int[] pixels(BufferedImage image) {
        return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
    }

    static long opaquePixels(BufferedImage image) {
        return IntStream.of(pixels(image)).filter(p -> (p >>> 24) > 0x80).count();
    }
}
