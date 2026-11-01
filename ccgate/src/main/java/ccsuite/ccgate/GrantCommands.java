package ccsuite.ccgate;

import module java.base;

/** ccacc gate grant add|list|revoke and ccacc gate key issue|revoke. Writes grants.json; the running gate picks it up. */
public final class GrantCommands {

    final GrantBook book;

    public GrantCommands(GrantBook book) {
        this.book = book;
    }

    public String run(List<String> args) throws IOException {
        if (args.size() < 2) return GateCli.HELP;
        var f = new Flags(args.subList(2, args.size()));
        return switch (args.get(0) + " " + args.get(1)) {
            case "grant add" -> add(f);
            case "grant list" -> list();
            case "grant revoke" -> revokeGrant(f.word(0));
            case "key issue" -> issue(f);
            case "key revoke" -> revokeKey(f.word(0));
            default -> GateCli.HELP;
        };
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
                .perMinute(Integer.parseInt(f.get("per-minute", "0"))).created(now.toString())
                .expires(now.plus(Duration.ofDays(Long.parseLong(f.get("days", "7")))).toString());
        g.grants().add(grant);
        book.save(g);
        return "grant '" + id + "' added: " + describe(grant) + "\nnext: ccacc gate key issue " + id;
    }

    String issue(Flags f) throws IOException {
        var g = book.current();
        var grant = g.byId(f.word(0)).orElseThrow(() -> new IllegalArgumentException("no grant '" + f.word(0) + "'"));
        var key = Keys.newKey();
        var now = Instant.now();
        var expires = f.has("days") ? now.plus(Duration.ofDays(Long.parseLong(f.get("days", "7")))).toString() : grant.expires();
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
        var g = book.current();
        g.byId(id).orElseThrow(() -> new IllegalArgumentException("no grant '" + id + "'")).revoked(true);
        book.save(g);
        return "grant '" + id + "' revoked";
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
                + (g.daily().isEmpty() ? "" : ", daily " + g.daily()) + (g.perMinute() > 0 ? ", " + g.perMinute() + "/min" : "")
                + ", until " + g.expires() + (g.purpose() == null ? "" : ", for: " + g.purpose());
    }
}
