package ccsuite.ccacc.migrate;

import ccsuite.base.config.CredentialsManager;
import ccsuite.ccacc.CcaccSettingsStore;
import ccsuite.localserver.config.SettingsManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * Retires the old XML-based ccu, cswap and ccls: finds them (see {@link LegacyScan}), shows the plan, and with
 * {@code --apply} brings their accounts and settings into the JSON files, stops them, puts their exes, jars,
 * Startup shortcuts and XML files into a dated archive (nothing is deleted), and switches on in ccacc the parts
 * that replace them. {@code --restore <name>} puts an archive back, {@code --list} names the archives.
 * Meant for any machine that has the old tools, not for one in particular.
 */
public final class Migrate {

    final Path home;
    final LegacyScan scan;
    final ArchiveStore archive;
    final Consumer<Path> stopper;
    final Consumer<String> out;

    public Migrate(Path home, LegacyScan scan, Consumer<Path> stopper, Consumer<String> out) {
        this.home = home;
        this.scan = scan;
        this.archive = new ArchiveStore(home.resolve("ccsuite").resolve("archive"));
        this.stopper = stopper;
        this.out = out;
    }

    public static int run(String[] args) {
        var home = Machine.home();
        var m = new Migrate(home, new LegacyScan(home, Machine.pathDirs(), Machine.startupDir(), Machine::doctor, Machine::shortcutTarget),
                exe -> { var n = Machine.stop(exe); if (n > 0) System.out.println("stopped " + n + " running " + exe.getFileName()); }, System.out::println);
        try {
            var a = List.of(args);
            if (a.contains("--list")) return m.list();
            var at = a.indexOf("--restore");
            if (at >= 0) return at + 1 < a.size() ? m.restore(a.get(at + 1)) : fail("--restore needs the name of an archive; --list shows them");
            return m.plan(a.contains("--apply"));
        } catch (IOException | jakarta.xml.bind.JAXBException e) {
            return fail("migrate failed: " + e);
        }
    }

    static int fail(String message) {
        System.err.println(message);
        return 1;
    }

    /** Shows what would be archived; with {@code apply} also does it. */
    public int plan(boolean apply) throws IOException, jakarta.xml.bind.JAXBException {
        var items = scan.scan();
        var tools = items.stream().filter(i -> i.kind().equals(LegacyItem.EXE)).map(LegacyItem::note).toList();
        if (items.isEmpty()) { out.accept("No old XML-based ccu, cswap or ccls found: nothing to migrate."); return 0; }
        out.accept("Old XML-based tools found" + (tools.isEmpty() ? "" : ": " + String.join(", ", tools)));
        items.forEach(i -> out.accept("  %-11s %s  (%s)".formatted(i.kind(), i.path(), i.note())));
        if (!apply) {
            out.accept("");
            out.accept("Nothing was changed. `ccacc migrate --apply` moves these into ~/ccsuite/archive/<date>/ (nothing is deleted),");
            out.accept("after bringing their accounts and settings into the JSON files, and turns on in ccacc what replaces them.");
            return 0;
        }
        bringIntoJson(items);
        tools.forEach(t -> items.stream().filter(i -> i.kind().equals(LegacyItem.EXE) && i.note().equals(t)).forEach(i -> stopper.accept(i.path())));
        var dependents = items.stream().filter(i -> i.kind().equals(LegacyItem.DEPENDENT)).map(LegacyItem::note).toList();
        var movable = items.stream().filter(i -> !i.kind().equals(LegacyItem.DEPENDENT))
                .filter(i -> !(i.kind().equals(LegacyItem.CREDENTIALS) && !dependents.isEmpty()))
                .filter(i -> i.replacedBy() == null || Files.exists(i.replacedBy())).toList();
        var stamp = archive.archive(movable);
        takeOver(tools);
        out.accept("Archived " + movable.size() + " files in " + archive.folder(stamp));
        if (!dependents.isEmpty()) out.accept("Kept the old accounts file: " + String.join(", ", dependents) + " still reads it. Replace it with a JSON-based build, then run migrate again.");
        out.accept("To undo: ccacc migrate --restore " + stamp);
        out.accept("Restart ccacc so it runs the parts that replace the old tools (" + String.join(", ", tools) + ").");
        return 0;
    }

    /** The JSON files exist and hold at least what the old files held. */
    void bringIntoJson(List<LegacyItem> items) throws IOException, jakarta.xml.bind.JAXBException {
        for (var i : items) {
            if (i.replacedBy() == null) continue;
            switch (i.kind()) {
                case LegacyItem.CREDENTIALS -> mergeCredentials(i.path(), i.replacedBy());
                case LegacyItem.SETTINGS -> importSettings(i);
                default -> { }
            }
        }
    }

    void mergeCredentials(Path xml, Path json) throws IOException, jakarta.xml.bind.JAXBException {
        var old = new CredentialsManager(xml).load();
        var manager = new CredentialsManager(json);
        if (!Files.exists(json)) { manager.save(old); out.accept("accounts: imported " + old.credentials().size() + " from the old file"); return; }
        var notes = new java.util.ArrayList<String>();
        manager.update(current -> notes.addAll(CredentialsMerge.merge(current, old)));
        notes.forEach(n -> out.accept("accounts: " + n));
        if (notes.isEmpty()) out.accept("accounts: the JSON already has everything in the old file");
    }

    void importSettings(LegacyItem item) throws jakarta.xml.bind.JAXBException, IOException {
        if (Files.exists(item.replacedBy())) return;
        if (item.note().equals("ccu")) new ccsuite.usagetracker.config.SettingsManager(item.replacedBy()).save(new ccsuite.usagetracker.config.SettingsManager(item.path()).load());
        else new SettingsManager(item.replacedBy()).save(new SettingsManager(item.path()).load());
        out.accept(item.note() + " settings: imported from the old file");
    }

    /** ccacc runs what it replaces: the watcher for cswap, the proxy for ccls, the widget (and card) for ccu. */
    void takeOver(List<String> tools) {
        var store = new CcaccSettingsStore(home.resolve("ccsuite").resolve("ccacc").resolve("settings.json"));
        var s = store.load();
        if (tools.contains("cswap")) s.watch(true);
        if (tools.contains("ccls")) s.server(true);
        if (tools.contains("ccu")) s.widget(true);
        store.save(s);
    }

    int list() throws IOException {
        var stamps = archive.stamps();
        if (stamps.isEmpty()) out.accept("No archives.");
        stamps.forEach(out);
        return 0;
    }

    int restore(String stamp) throws IOException {
        if (!archive.stamps().contains(stamp)) return fail("no archive named " + stamp + "; --list shows them");
        archive.restore(stamp).forEach(out);
        return 0;
    }
}
