package ccsuite.ccacc;

import module java.base;
import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/** The tray picture of the free accounts: one thin bar per account, as tall as its five-hour usage, coloured by how full it is. */
final class FreeTrayIcon {

    static BufferedImage draw(List<FreeGauges.Row> rows, int size) {
        var image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        var g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(0x1d3238));
            g.fillRoundRect(0, 0, size, size, size / 4, size / 4);
            var n = Math.max(1, Math.min(rows.size(), 12));
            var pad = Math.max(1, size / 10);
            var slot = (size - 2.0 * pad) / n;
            var inner = size - 2 * pad;
            for (var i = 0; i < n; i++) {
                var five = i < rows.size() ? rows.get(i).five() : null;
                var h = five == null ? Math.max(1, size / 12) : Math.max(1, (int) Math.round(inner * Math.min(1, five)));
                g.setColor(Color.decode(FreeSummary.colour(five)));
                var w = Math.max(1, (int) Math.floor(slot) - (slot >= 3 ? 1 : 0));
                g.fillRect((int) Math.round(pad + i * slot), pad + inner - h, w, h);
            }
        } finally {
            g.dispose();
        }
        return image;
    }

    private FreeTrayIcon() {}
}
