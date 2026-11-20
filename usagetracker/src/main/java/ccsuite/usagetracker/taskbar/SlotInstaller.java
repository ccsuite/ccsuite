package ccsuite.usagetracker.taskbar;

import module java.base;

/** Pulls the taskbar slot DLL out of the jar and registers it for the current user (no admin); the work is done by {@link ccsuite.taskbarslot.SlotInstaller}. */
public final class SlotInstaller {
    private SlotInstaller() {}

    public static Path directory() { return ccsuite.taskbarslot.SlotInstaller.directory(); }

    public static Path install() throws IOException, InterruptedException { return ccsuite.taskbarslot.SlotInstaller.install(); }

    public static void uninstall() throws IOException, InterruptedException { ccsuite.taskbarslot.SlotInstaller.uninstall(); }

    public static Optional<String> registeredPath() throws IOException, InterruptedException { return ccsuite.taskbarslot.SlotInstaller.registeredPath(); }
}
