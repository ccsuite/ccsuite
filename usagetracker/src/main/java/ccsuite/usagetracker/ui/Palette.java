package ccsuite.usagetracker.ui;

/**
 * The colours that tell the paid usage window and the free one apart (the card has the same pairs, see CardView): the row background (active or off), the bars of the
 * time gone (five-hour, week) and an accent. A null bar colour leaves the row's own.
 */
public record Palette(String bgActive, String bgInactive, String timeBar, String weekBar, String accent) {

    public static final Palette PAID = new Palette("#2A2A2A", "#3A2A2A", null, null, "#00BFFF");

    public static final Palette FREE = new Palette("#1D3238", "#2A3A3F", "#26C6DA", "#80DEEA", "#26C6DA");
}
