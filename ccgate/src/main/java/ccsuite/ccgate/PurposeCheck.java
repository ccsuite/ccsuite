package ccsuite.ccgate;

import module java.base;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The after-the-fact purpose check: each new user turn of a person grant that has a purpose is judged by Haiku, in the
 * background, after the call has gone out ("within purpose: yes or no, one-line reason"). Tools are not checked; their
 * prompts are fixed. The verdict and reason go into the ledger, never the turn. A "no" raises a flag for the owner and
 * puts a note into that session's next request, so Claude corrects course and tells the user. Nothing is blocked.
 */
public final class PurposeCheck {

    /** What the judge said, with the ledger line of its own call (tokens, points, account), or null when none was made. */
    public record Judgement(String verdict, String reason, LedgerEntry call) {
        /** Only a clear "no" is a flag; "unclear" is recorded as such and not raised. */
        public boolean within() { return !"no".equals(verdict); }
    }

    /** Who judges a turn against a purpose. */
    public interface Judge {
        /** {@code previous} is the user's turn before this one, for context only; null when there is none. */
        Judgement judge(String purpose, String previous, String turn) throws Exception;
    }

    /** One off-purpose verdict, for the owner. */
    public record Flag(String grant, String session, String reason, Instant at) { }

    static final int MAX_TURN_CHARS = 4000, REMEMBERED = 2000;
    static final Pattern REMINDER = Pattern.compile("(?s)<system-reminder>.*?</system-reminder>");
    static final Pattern VERDICT = Pattern.compile("(?i)^[\\W_]*(yes|no)\\b[\\s:.,*-]*");

    final Judge judge;
    final Ledger ledger;
    volatile Consumer<Flag> onFlag = f -> { };
    final Map<String, String> notes = new ConcurrentHashMap<>();
    final Set<String> seen = Collections.newSetFromMap(Collections.synchronizedMap(new LinkedHashMap<>(256, 0.75f, false) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Boolean> e) { return size() > REMEMBERED; }
    }));

    public PurposeCheck(Judge judge, Ledger ledger) {
        this.judge = judge;
        this.ledger = ledger;
    }

    public PurposeCheck onFlag(Consumer<Flag> c) {
        onFlag = c == null ? f -> { } : c;
        return this;
    }

    public static boolean applies(Call c) {
        var g = c.grant();
        return g != null && "person".equals(g.kind()) && g.purpose() != null && !g.purpose().isBlank();
    }

    /** Called once the call has gone out: starts a check in the background when this is a new user turn. */
    public void after(Call c, ObjectNode body) {
        var t = start(c, body);
        if (t != null) Thread.ofVirtual().name("ccgate-purpose").start(t);
    }

    /** The check to run for this call, or null when there is nothing new to check. */
    Runnable start(Call c, ObjectNode body) {
        if (!applies(c) || body == null) return null;
        var turn = newUserTurn(body);
        if (turn == null) return null;
        if (!seen.add(scope(c) + "|" + Keys.fingerprint(turn))) return null;
        var previous = previousUserTurn(body);
        return () -> run(c, previous, turn);
    }

    void run(Call c, String previous, String turn) {
        var g = c.grant();
        LedgerEntry e;
        try {
            var j = judge.judge(g.purpose(), previous, turn);
            e = j.call() != null ? j.call() : new LedgerEntry();
            e.purpose(j.verdict()).purposeReason(j.reason());
            if (!j.within()) {
                notes.put(scope(c), j.reason());
                onFlag.accept(new Flag(g.id(), c.session(), j.reason(), Instant.now()));
            }
        } catch (Exception ex) {
            e = new LedgerEntry().purpose("unchecked").reason(String.valueOf(ex.getMessage()));
        }
        e.t(Instant.now().toString()).outcome("purpose-check").grant(g.id())
                .key(c.keyFingerprint() == null ? null : c.keyFingerprint().substring(0, 12)).session(c.session()).agent(c.agent());
        ledger.append(e);
    }

    /** The note for this call's session after an off-purpose verdict, given once; null when there is none. */
    public String noteFor(Call c) {
        if (!applies(c)) return null;
        var reason = notes.remove(scope(c));
        if (reason == null) return null;
        return "ccgate note: the user's previous request in this session was judged outside this grant's purpose (\""
                + c.grant().purpose().strip() + "\"): " + reason + ". Do only what fits the purpose. If the current request is outside"
                + " it too, say so to the user plainly, and explain that a wider grant can be asked from the provider who issued it.";
    }

    /** A session when the caller sends one, else the grant: whatever the note and the dedup are kept per. */
    static String scope(Call c) {
        return c.session() != null ? "s:" + c.session() : "g:" + c.grant().id();
    }

    /**
     * The text of the last message when it is a new turn by the user: role user with text of its own. A message that holds
     * only tool results, or only system reminders, is part of the same turn and returns null.
     */
    static String newUserTurn(ObjectNode body) {
        var messages = body.path("messages");
        if (!messages.isArray() || messages.isEmpty()) return null;
        return ownText(messages.get(messages.size() - 1), MAX_TURN_CHARS);
    }

    /** The user's turn before the last one (its own text), for the judge's context; null when there is none. */
    static String previousUserTurn(ObjectNode body) {
        var messages = body.path("messages");
        for (var i = messages.size() - 2; i >= 0; i--) {
            var t = ownText(messages.get(i), 1000);
            if (t != null) return t;
        }
        return null;
    }

    /** A user message's own text (no tool results, no system reminders), cut to {@code max} characters; null when it has none. */
    static String ownText(JsonNode message, int max) {
        if (!"user".equals(message.path("role").asText())) return null;
        var sb = new StringBuilder();
        var content = message.path("content");
        if (content.isTextual()) sb.append(content.asText());
        else for (JsonNode b : content)
            if ("text".equals(b.path("type").asText())) sb.append(b.path("text").asText()).append('\n');
        var text = REMINDER.matcher(sb).replaceAll("").strip();
        if (text.isEmpty()) return null;
        return text.length() > max ? text.substring(0, max) : text;
    }

    /** "yes: reason" or "no: reason", on the first line that starts with either; anything else is "unclear", never a quiet yes. */
    static Judgement parse(String answer, LedgerEntry call) {
        for (var line : (answer == null ? "" : answer).lines().toList()) {
            var m = VERDICT.matcher(line.strip());
            if (!m.find()) continue;
            var reason = line.strip().substring(m.end()).strip();
            if (reason.length() > 200) reason = reason.substring(0, 200);
            var verdict = m.group(1).toLowerCase(Locale.ROOT);
            return new Judgement(verdict, reason.isEmpty() ? (verdict.equals("yes") ? "within purpose" : "outside purpose") : reason, call);
        }
        return new Judgement("unclear", "the check gave no clear yes or no", call);
    }
}
