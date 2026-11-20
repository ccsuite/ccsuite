package ccsuite.usagetracker.taskbar;

/** ccacc's cards in the shared band: the free accounts' card (first) or the usage card (after it, or alone). The hosting itself lives in {@code ccsuite.taskbarslot}. */
final class Slot extends ccsuite.taskbarslot.Slot {
    /** The free accounts' card (first in the band) or the usage card (after it, or alone). */
    final boolean free;

    Slot(String title, boolean free) {
        super(title, () -> where(SlotLayout.cards(), bandCards(), SlotLayout.index(free)), false);
        this.free = free;
    }

    /** How many cards long ccacc's part of the band is: what Explorer has loaded, not what was asked for (1, or 2 for the two-card band). */
    static int bandCards() { return legacy(); }

    /** {first slot, slots spanned} of a card that wants slot {@code index} of {@code wanted} cards, in ccacc's part of {@code bandCards} slots: it shares them, or fills them when they cannot hold all the wanted cards. */
    static int[] where(int wanted, int bandCards, int index) {
        var n = Math.min(wanted, bandCards);
        var span = Math.max(1, bandCards / n);
        return new int[]{(n == wanted ? index : 0) * span, span};
    }

    @Override public boolean embed() {
        if (free && bandCards() != 2) return false;   // an old slot DLL is still loaded: the band is one card long, so the free card waits (the usage card keeps the whole band)
        return super.embed();
    }
}
