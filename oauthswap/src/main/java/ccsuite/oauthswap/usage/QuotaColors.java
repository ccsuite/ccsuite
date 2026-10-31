package ccsuite.oauthswap.usage;

import ccsuite.oauthclient.*;

import picocli.CommandLine.Help.Ansi;

/** Maps quota severity to terminal color via picocli's Ansi (auto-disables when not a tty). */
public final class QuotaColors {

    private QuotaColors() {}

    public static String paint(QuotaHealth h, String text) {
        var style = switch (h) {
            case DANGER -> "red,bold";
            case WARN -> "yellow";
            case OK -> "green";
            case UNKNOWN -> "faint";
        };
        return Ansi.AUTO.string("@|" + style + " " + text + "|@");
    }

    public static String danger(String text) { return Ansi.AUTO.string("@|red,bold " + text + "|@"); }
    public static String warn(String text)   { return Ansi.AUTO.string("@|yellow " + text + "|@"); }
    public static String ok(String text)     { return Ansi.AUTO.string("@|green " + text + "|@"); }
}
