package ccsuite.cclocalusagestats.report;

import ccsuite.cclocalusagestats.model.*;
import java.time.*;
import picocli.CommandLine.Option;

public final class Filters {

    @Option(names = "--since", description = "first day, yyyy-MM-dd, in --zone")
    LocalDate since;
    @Option(names = "--until", description = "last day, yyyy-MM-dd, in --zone")
    LocalDate until;
    @Option(names = "--zone", description = "reporting timezone, default: this machine's")
    ZoneId zone = ZoneId.systemDefault();
    @Option(names = "--project", description = "only working directories containing this text")
    String project;
    @Option(names = "--model", description = "only models containing this text")
    String model;
    @Option(names = "--account", description = "only this account id, e.g. ABC")
    String account;
    @Option(names = "--prp", description = "only this PRP number")
    String prp;
    @Option(names = "--session", description = "only sessions starting with this id")
    String session;
    @Option(names = "--top", description = "rows to show, default 40; 0 = all")
    int top = 40;
    @Option(names = "--json", description = "print JSON instead of a table")
    boolean json;

    public Filters since(LocalDate d) {
        since = d;
        return this;
    }

    public Filters top(int n) {
        top = n;
        return this;
    }

    public ZoneId zone() {
        return zone;
    }

    public int top() {
        return top;
    }

    public boolean json() {
        return json;
    }

    public boolean accepts(UsageRow r) {
        return ok(r.ts(), r.cwd(), r.prp(), r.sessionId())
            && (model == null || has(r.model(), model))
            && (account == null || account.equalsIgnoreCase(r.account()));
    }

    public boolean acceptsDay(long ts) {
        var day = Instant.ofEpochMilli(ts).atZone(zone).toLocalDate();
        return (since == null || !day.isBefore(since)) && (until == null || !day.isAfter(until));
    }

    public boolean acceptsActivity(long minute, SessionInfo s) {
        return ok(minute * 60_000, s == null ? null : s.cwd(), s == null ? null : s.prp(), s == null ? null : s.sessionId());
    }

    boolean ok(long ts, String cwd, String prpNo, String sid) {
        var day = Instant.ofEpochMilli(ts).atZone(zone).toLocalDate();
        return (since == null || !day.isBefore(since))
            && (until == null || !day.isAfter(until))
            && (project == null || has(cwd, project))
            && (prp == null || prp.equals(prpNo))
            && (session == null || (sid != null && sid.startsWith(session)));
    }

    static boolean has(String s, String part) {
        return s != null && s.toLowerCase().contains(part.toLowerCase());
    }
}
