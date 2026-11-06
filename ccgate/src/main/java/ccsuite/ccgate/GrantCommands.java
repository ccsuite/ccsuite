package ccsuite.ccgate;

import module java.base;

/**
 * ccacc gate grant add|list|revoke|ban|unban|rate|throttle and ccacc gate key issue|revoke. Writes grants.json; the
 * running gate picks it up. Issuing (grant add, key issue) gives power, so it needs the owner's yes at a window: the
 * {@link IssueGuard} is that window inside the running ccacc, and without one nothing is issued. Everything else only
 * takes power away or lists, and needs no approval.
 */
public final class GrantCommands {

    /** The owner's yes for one issuing, shown as a plain sentence of what would be issued. */
    @FunctionalInterface
    public interface IssueGuard {
        boolean approve(String what);
    }

    final GrantBook book;
    final IssueGuard guard;

    /** Without a guard: listing and restricting work, issuing is refused. */
    public GrantCommands(GrantBook book) {
        this(book, null);
    }

    public GrantCommands(GrantBook book, IssueGuard guard) {
        this.book = book; this.guard = guard;
    }

    /** True for the commands that issue something and so need approval (and a running ccacc). */
    public static boolean issues(List<String> args) {
        return args.size() >= 2 && (args.get(0) + " " + args.get(1)).matches("grant add|grant edit|key issue");
    }

    public String run(List<String> args) throws IOException {
        if (args.size() < 2) return GateCli.HELP;
        var f = new Flags(args.subList(2, args.size()));
        return switch (args.get(0) + " " + args.get(1)) {
            case "grant add" -> add(f);
            case "grant edit" -> edit(f);
            case "grant list" -> list();
            case "grant revoke" -> revokeGrant(f.word(0));
            case "grant ban" -> ban(f);
            case "grant unban" -> unban(f.word(0));
            case "grant rate" -> rate(f);
            case "grant throttle" -> throttle(f);
            case "key issue" -> issue(f);
            case "key revoke" -> revokeKey(f.word(0));
            default -> GateCli.HELP;
        };
    }

    void approved(String what) {
        if (guard == null)
            throw new IllegalStateException("issuing needs the owner's approval at the ccacc window, and ccacc is not running. Start ccacc and run the command again.");
        if (!guard.approve(what)) throw new IllegalStateException("the owner declined: nothing was issued");
    }

    String add(Flags f) throws IOException {
        var g = book.current();
        var id = f.word(0);
        if (g.byId(id).isPresent()) throw new IllegalArgumentException("grant '" + id + "' exists; revoke it or choose another name");
        var kind = f.get("kind", "tool");
        var models = Flags.list(f.get("models", kind.equals("person") ? "haiku,sonnet" : "haiku"));
        var now = Instant.now();
        var grant = new Grant().id(id).name(f.get("name", id)).kind(kind).purpose(f.get("purpose", null)).email(f.get("email", null))
                .models(models).budget(Flags.points(f.get("budget", null))).daily(Flags.points(f.get("daily", null)))
                .perMinute(Integer.parseInt(f.get("per-minute", "0"))).smooth(f.has("smooth")).created(now.toString())
                .expires(now.plus(Duration.ofDays(Long.parseLong(f.get("days", "7")))).toString());
        approved("Create grant '" + id + "': " + describe(grant));
        g = book.current();
        g.grants().add(grant);
        book.save(g);
        return "grant '" + id + "' added: " + describe(grant) + "\nnext: ccacc gate key issue " + id;
    }

    /**
     * ccacc gate grant edit &lt;name&gt; [--models ...] [--budget ...] [--daily ...] [--per-minute n] [--smooth [false]] [--days n] [--purpose "..."]:
     * changes a grant that exists, keeping its keys. Changing what a caller may do is giving power, so it asks the owner
     * like issuing does, and the window lists each change. {@code --budget} and {@code --daily} replace the whole list.
     */
    String edit(Flags f) throws IOException {
        var id = f.word(0);
        var now = Instant.now();
        var before = book.current().byId(id).orElseThrow(() -> new IllegalArgumentException("no grant '" + id + "'"));
        var changes = new ArrayList<String>();
        if (f.has("models")) changes.add("models " + before.models() + " -> " + Flags.list(f.get("models", "")));
        if (f.has("budget")) changes.add("total budget " + before.budget() + " -> " + Flags.points(f.get("budget", "")));
        if (f.has("daily")) changes.add("daily caps " + before.daily() + " -> " + Flags.points(f.get("daily", "")));
        if (f.has("per-minute")) changes.add("calls a minute " + before.perMinute() + " -> " + Integer.parseInt(f.get("per-minute", "0")) + " (0 = no limit)");
        if (f.has("smooth")) changes.add("excess calls " + (smoothFlag(f) ? "wait their turn" : "are refused"));
        if (f.has("days")) changes.add("valid until " + before.expires() + " -> " + now.plus(Duration.ofDays(Long.parseLong(f.get("days", "7")))));
        if (f.has("purpose")) changes.add("purpose -> " + f.get("purpose", ""));
        if (changes.isEmpty()) throw new IllegalArgumentException("nothing to change: give at least one of --models --budget --daily --per-minute --smooth --days --purpose");
        approved("Change grant '" + id + "' (its keys stay valid): " + String.join("; ", changes) + ". Now: " + describe(before));
        var grants = book.current();
        var g = grants.byId(id).orElseThrow(() -> new IllegalArgumentException("no grant '" + id + "'"));
        if (f.has("models")) g.models(Flags.list(f.get("models", "")));
        if (f.has("budget")) g.budget(Flags.points(f.get("budget", "")));
        if (f.has("daily")) g.daily(Flags.points(f.get("daily", "")));
        if (f.has("per-minute")) g.perMinute(Integer.parseInt(f.get("per-minute", "0")));
        if (f.has("smooth")) g.smooth(smoothFlag(f));
        if (f.has("days")) g.expires(now.plus(Duration.ofDays(Long.parseLong(f.get("days", "7")))).toString());
        if (f.has("purpose")) g.purpose(f.get("purpose", ""));
        book.save(grants);
        return "grant '" + id + "' changed: " + describe(g);
    }

    static boolean smoothFlag(Flags f) {
        return !"false".equals(f.get("smooth", "true"));
    }

    String issue(Flags f) throws IOException {
        var g = book.current();
        var grant = g.byId(f.word(0)).orElseThrow(() -> new IllegalArgumentException("no grant '" + f.word(0) + "'"));
        var now = Instant.now();
        var expires = f.has("days") ? now.plus(Duration.ofDays(Long.parseLong(f.get("days", "7")))).toString() : grant.expires();
        approved("Issue a new key for grant '" + grant.id() + "'" + (f.get("label", null) == null ? "" : " (" + f.get("label", null) + ")")
                + ", valid until " + expires + ". The grant: " + describe(grant));
        g = book.current();
        grant = g.byId(f.word(0)).orElseThrow(() -> new IllegalArgumentException("no grant '" + f.word(0) + "'"));
        var key = Keys.newKey();
        grant.keys().add(new KeyRef().fingerprint(Keys.fingerprint(key)).label(f.get("label", null)).issued(now.toString()).expires(expires));
        book.save(g);
        return key + "\n(shown once: give it to the caller as its x-api-key; fingerprint " + Keys.fingerprint(key).substring(0, 12) + ")";
    }

    String list() {
        var g = book.current().grants();
        if (g.isEmpty()) return "no grants yet: ccacc gate grant add <name> ...";
        var sb = new StringBuilder();
        for (var x : g) {
            sb.append(x.revoked() ? "[revoked] " : "").append(x.id()).append(": ").append(describe(x)).append('\n');
            for (var k : x.keys())
                sb.append("    key ").append(k.fingerprint(), 0, 12).append(k.label() == null ? "" : " (" + k.label() + ")")
                        .append(" until ").append(k.expires()).append(k.revoked() ? " [revoked]" : "").append('\n');
        }
        return sb.toString().stripTrailing();
    }

    String revokeGrant(String id) throws IOException {
        new Clients(book, null, null).kick(id);
        return "grant '" + id + "' revoked (and all its keys)";
    }

    /** ccacc gate grant ban &lt;name&gt; [--hours 24] [--reason "..."]: suspended until then; unban lifts it. */
    String ban(Flags f) throws IOException {
        var hours = Double.parseDouble(f.get("hours", "24"));
        new Clients(book, null, null).suspend(f.word(0), Duration.ofMinutes(Math.round(hours * 60)), f.get("reason", null));
        return "grant '" + f.word(0) + "' suspended for " + hours + " h";
    }

    String unban(String id) throws IOException {
        new Clients(book, null, null).lift(id);
        return "grant '" + id + "' is no longer suspended";
    }

    /** ccacc gate grant rate &lt;name&gt; &lt;per-minute&gt; [--smooth]: 0 removes the limit; --smooth queues the excess instead of refusing it. */
    String rate(Flags f) throws IOException {
        var n = Integer.parseInt(f.word(1));
        new Clients(book, null, null).rate(f.word(0), n, f.has("smooth"));
        return "grant '" + f.word(0) + "': " + (n == 0 ? "no rate limit" : n + " calls a minute" + (f.has("smooth") ? ", the excess waits its turn" : ", the excess is refused"));
    }

    /** ccacc gate grant throttle &lt;name&gt; &lt;bytes-per-second&gt;: 0 removes the limit. */
    String throttle(Flags f) throws IOException {
        var bps = Integer.parseInt(f.word(1));
        new Clients(book, null, null).throttle(f.word(0), bps);
        return "grant '" + f.word(0) + "': " + (bps == 0 ? "answers at full speed" : "answers delivered at " + Clients.speed(bps));
    }

    String revokeKey(String prefix) throws IOException {
        var g = book.current();
        var hits = g.grants().stream().flatMap(x -> x.keys().stream()).filter(k -> k.fingerprint().startsWith(prefix)).toList();
        if (hits.size() != 1) throw new IllegalArgumentException(hits.size() + " keys start with '" + prefix + "'; give more of the fingerprint");
        hits.get(0).revoked(true);
        book.save(g);
        return "key " + prefix + " revoked";
    }

    static String describe(Grant g) {
        return g.kind() + ", models " + g.models() + (g.budget().isEmpty() ? "" : ", budget " + g.budget())
                + (g.daily().isEmpty() ? "" : ", daily " + g.daily())
                + (g.perMinute() > 0 ? ", " + g.perMinute() + "/min" + (g.smooth() ? " (queued)" : "") : "")
                + (g.throttleBps() > 0 ? ", answers at " + Clients.speed(g.throttleBps()) : "")
                + (g.suspended(Instant.now()) ? ", SUSPENDED until " + g.bannedUntil() : "")
                + ", until " + g.expires() + (g.purpose() == null ? "" : ", for: " + g.purpose());
    }
}
