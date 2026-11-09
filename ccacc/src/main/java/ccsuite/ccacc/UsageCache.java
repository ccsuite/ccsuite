package ccsuite.ccacc;

import module java.base;
import ccsuite.base.model.CCAPICredential;
import ccsuite.base.model.CCAPIsCredentials;
import ccsuite.oauthclient.AccountUsage;
import ccsuite.oauthswap.usage.UsageService;
import ccsuite.usagetracker.model.LastReadings;

/**
 * What the app remembers across a restart: the readings log. A reading is costly (a real message per account) and a burst of them risks HTTP errors, so at start the
 * usage source is seeded from the log: an account read a few minutes ago is not probed again until its cooldown has passed. A reading whose window has reset since is
 * obsolete (the account has moved on) and is left for a probe.
 */
final class UsageCache {

    static final long WEEK_SECONDS = 7 * 24 * 3600L;

    /** Seeds {@code usage} with the logged reading of every paid account that has one still current; returns how many. */
    static int seed(UsageService usage, List<CCAPICredential> credentials, Map<String, LastReadings.Reading> logged, Instant now) {
        var n = 0;
        for (var c : credentials) {
            var r = logged.get(c.id());
            if (r == null || !CCAPIsCredentials.paid(c) || !current(r, now)) continue;
            usage.seed(c.id(), usage(r, now), r.t().toEpochMilli());
            n++;
        }
        return n;
    }

    /** No window of the reading has reset since it was made. */
    static boolean current(LastReadings.Reading r, Instant now) {
        return ahead(r.reset5h(), now) && ahead(r.reset7d(), now);
    }

    static boolean ahead(OffsetDateTime reset, Instant now) { return reset == null || reset.toInstant().isAfter(now); }

    static AccountUsage usage(LastReadings.Reading r, Instant now) {
        var week = r.reset7d() == null ? null : Math.max(0, Math.min(100, (WEEK_SECONDS - Duration.between(now, r.reset7d().toInstant()).getSeconds()) * 100.0 / WEEK_SECONDS));
        return new AccountUsage(r.u5h(), r.reset5h() == null ? null : r.reset5h().toString(), r.u7d(), r.reset7d() == null ? null : r.reset7d().toString(), week);
    }

    private UsageCache() {}
}
