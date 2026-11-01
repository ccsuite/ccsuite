package ccsuite.ccgate;

import module java.base;

/** The command words of the gate: "gate ..." (grants and keys) and "ledger ..." (reports). */
public final class GateCli {

    public static final String HELP = String.join("\n",
            "ccacc gate grant add <name> [--kind tool|person] [--models haiku,sonnet,opus] [--budget sonnet=20,haiku=100]",
            "                     [--daily sonnet=5] [--per-minute 30] [--days 7] [--purpose \"...\"] [--email ann@example.org]",
            "ccacc gate grant list | revoke <name>",
            "ccacc gate key issue <grant> [--label laptop] [--days 7]     prints the key once",
            "ccacc gate key revoke <fingerprint-prefix>",
            "ccacc gate relay url <https://relay.example.org>      where the relay part connects (ccacc part relay on)",
            "ccacc gate relay secret [--new]       makes the provider secret; prints only its SHA-256, for the relay server",
            "ccacc ledger [--since 2026-11-01] [--until 2026-11-02] [--by grant|job|project|model|account|day|session] [--job <tag>] [--tail]",
            "points: 1 point = 1% of one account's 5-hour window, priced per call from its tokens");

    /** Runs "gate ..." or "ledger ..." and returns what to print. */
    public static String run(String word, List<String> args) throws IOException {
        return switch (word) {
            case "gate" -> new GrantCommands(GrantBook.open()).run(args);
            case "ledger" -> new LedgerReport(Ledger.standard()).run(new Flags(args));
            default -> HELP;
        };
    }

    private GateCli() { }
}
