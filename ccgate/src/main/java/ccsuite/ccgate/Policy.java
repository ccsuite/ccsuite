package ccsuite.ccgate;

import module java.base;
import static ccsuite.ccgate.Refusal.of;

/** Decides whether a call may go out: a known, live key; a live grant; an allowed model; budget and rate left. */
public final class Policy {

    /** How long a call of a smoothed grant may wait for a free slot before it is refused after all. */
    static final Duration SMOOTH_MAX = Duration.ofSeconds(30);

    final Spend spend;

    public Policy(Spend spend) {
        this.spend = spend;
    }

    /** Empty when the call may go out. */
    public Optional<Refusal> check(Call c, Instant now) {
        return Optional.ofNullable(refusal(c, now));
    }

    Refusal refusal(Call c, Instant now) {
        if (c.key() == null) return of(401, "This endpoint needs a ccgate key in x-api-key. Ask the provider for one.");
        var g = c.grant();
        if (g == null) return of(401, "Unknown ccgate key. Ask the provider for a grant.");
        var name = "Grant '" + g.id() + "'";
        var k = c.keyRef();
        if (g.revoked() || k.revoked()) return of(403, name + (g.revoked() ? " was revoked." : ": this key was revoked."));
        if (g.expired(now)) return of(403, name + " expired at " + g.expires() + ". Ask the provider to extend it.");
        if (k.expires() != null && now.isAfter(Instant.parse(k.expires())))
            return of(403, name + ": this key expired at " + k.expires() + ". Ask for a new key.");
        if (g.suspended(now))
            return of(403, name + " is suspended until " + g.bannedUntil() + (g.banReason() == null ? "" : " (" + g.banReason() + ")") + ". Ask the provider.");
        var f = c.family();
        if ("none".equals(f)) return null;
        if (!g.models().contains(f))
            return of(403, name + " does not allow " + f + " models (allowed: " + String.join(", ", g.models()) + ").");
        var cap = g.budget().get(f);
        if (cap != null && spend.total(g.id(), f) >= cap)
            return of(429, name + " has spent its " + f + " budget: " + pts(spend.total(g.id(), f)) + " of " + pts(cap) + " points. Ask the provider for more.");
        var day = g.daily().get(f);
        if (day != null && spend.today(g.id(), f) >= day)
            return of(429, name + " has spent today's " + f + " budget: " + pts(spend.today(g.id(), f)) + " of " + pts(day) + " points. It renews at 00:00 UTC.");
        if (!(g.smooth() ? spend.awaitTick(g.id(), g.perMinute(), SMOOTH_MAX) : spend.tick(g.id(), g.perMinute())))
            return of(429, name + " allows " + g.perMinute() + " requests a minute. Slow down and retry.");
        return null;
    }

    static String pts(double p) {
        return String.format(Locale.ROOT, "%.2f", p);
    }
}
