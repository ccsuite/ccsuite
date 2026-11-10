package ccsuite.ccacc.trim;

/** The tray's one row for the trimmer: a switch with, when on, how many MB it gave back today. Read from the settings and state files, so it needs nothing from the loop. */
public final class TrimMenu {

    public static String label() {
        var on = TrimSettingsStore.load().enabled();
        var text = (on ? "● " : "○ ") + "Trim idle Claude sessions";
        if (!on) return text;
        return text + String.format(java.util.Locale.ROOT, "   (%.0f MB given back today)", TrimState.load(TrimState.file()).today(System.currentTimeMillis()));
    }

    public static void toggle() { TrimSettingsStore.save(TrimSettingsStore.load().enabled(!TrimSettingsStore.load().enabled())); }

    private TrimMenu() {}
}
