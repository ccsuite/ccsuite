package ccsuite.ccacc;

import module java.base;

/**
 * PRP 88, the terms model of a free call's cost, in points of the five-hour window: per call, the fresh prompt, the chat's held conversation (read from the cache while warm, priced cold
 * after {@code termsWarmSeconds} without a call) and the reply. The weights are the measured settings ({@link LaneSettings#termsFreshPer10k} and the rest; Haiku is {@code haikuFactor} of
 * them). Two layers learn from the gauge: a SCALE per model family (slow: the gauge movement against the model over windows of {@link #WINDOW_POINTS}, so a one-point step is never
 * fitted alone), and a FACTOR per chat (fast: the chat's own movement against the scaled model, shrunk by {@link #PRIOR_POINTS} so that 2 or 3 calls set it). A chat's expected
 * reply is the average of its replies so far. Rebuilt from the ledger after a restart (the calls carry what it learns from), so nothing learned is lost.
 */
final class FreeCostModel {

    static final double WINDOW_POINTS = 20, ALPHA = 0.3, PRIOR_POINTS = 2, MIN_POINTS = 0.1, CLIP = 3, CLIP_POINTS = 2;

    /** What the lane knows about a call before it starts. {@code chat} identifies the chat (account and session); {@code gapS} null for a new chat. */
    record Input(String model, String cache, double promptChars, double heldChars, Long gapS, String chat) {}

    private static final class Chat { double observed, predicted, reply; int replies; }

    private final LaneSettings s;
    private final Map<String, double[]> windows = new HashMap<>();   // family -> {model sum, gauge sum}
    private final Map<String, Double> scales = new HashMap<>();
    private final Map<String, Chat> chats = new LinkedHashMap<>(256, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Chat> e) { return size() > 500; }
    };

    FreeCostModel(LaneSettings s) { this.s = s; }

    private double factor(String model) { return FreeEstimator.family(model).equals("haiku") ? s.haikuFactor() : 1.0; }

    boolean warm(Long gapS) { return gapS == null || gapS < s.termsWarmSeconds(); }

    /** The model's cost before any learning, for a reply of this size. */
    double raw(Input in, double replyChars) {
        var held = "new".equals(in.cache()) ? 0 : in.heldChars();
        return factor(in.model()) * (s.termsPerCall() + s.termsFreshPer10k() * in.promptChars() / 1e4 + (warm(in.gapS()) ? s.termsReadPer10k() : s.termsColdPer10k()) * held / 1e4
                + s.termsReplyPer10k() * replyChars / 1e4);
    }

    synchronized double scale(String model) { return scales.getOrDefault(FreeEstimator.family(model), 1.0); }

    /** The reply this chat is expected to give: the mean of its replies, else the setting. */
    synchronized double expectedReply(String chat) {
        var c = chat == null ? null : chats.get(chat);
        return c == null || c.replies == 0 ? s.termsReplyChars() : c.reply;
    }

    /** The chat's own correction (1 until its calls have been seen by the gauge). */
    synchronized double chatFactor(String chat) {
        var c = chat == null ? null : chats.get(chat);
        return c == null ? 1.0 : Math.clamp((c.observed + PRIOR_POINTS) / (c.predicted + PRIOR_POINTS), 0.5, 2.0);
    }

    /** The predicted cost of a call. */
    synchronized double predict(Input in) {
        var chatF = "new".equals(in.cache()) ? 1.0 : chatFactor(in.chat());
        return Math.max(MIN_POINTS, raw(in, expectedReply(in.chat())) * scale(in.model()) * chatF);
    }

    /** The largest fresh prompt (characters) a new chat can carry in this much room, keeping the margin. */
    double maxChars(double roomPoints, String model) {
        var in = new Input(model, "new", 0, 0, null, null);
        var sc = scale(model);
        var left = roomPoints - s.guardMarginPoints() - raw(in, s.termsReplyChars()) * sc;
        return Math.max(0, left / (factor(model) * s.termsFreshPer10k() * sc) * 1e4);
    }

    /** The input of a ledger call (null when it lacks what the model needs: the prompt size, and the held size of a continued chat). */
    static Input input(FreeLedger.Call c) {
        if (c.promptChars() == null) return null;
        if (!"new".equals(c.cache()) && c.heldChars() == null) return null;
        return new Input(c.model(), c.cache(), c.promptChars(), c.heldChars() == null ? 0 : c.heldChars(), c.gapS(), chatOf(c.account(), c.session()));
    }

    static String chatOf(String account, String session) { return account + "|" + session; }

    /** A served call teaches the model: the chat's reply size always; the scale and the chat's factor when its gauge movement is its own. */
    synchronized void learn(FreeLedger.Call c) {
        if (c.account() == null || c.status() >= 400) return;
        var in = input(c);
        if (in == null) return;
        var chat = in.chat();
        if ("new".equals(c.cache())) chats.remove(chat);
        var ch = chats.computeIfAbsent(chat, k -> new Chat());
        if (c.observed() != null && c.alone()) {
            var r = raw(in, c.replyChars() == null ? expectedReply(chat) : c.replyChars());
            var f = FreeEstimator.family(c.model());
            var w = windows.computeIfAbsent(f, k -> new double[2]);
            w[0] += Math.min(r, CLIP * c.observed() + CLIP_POINTS);   // one call never moves the scale alone: a jump the model cannot explain (measured: 25 points on a 1.6-point call) is the chat's, not the family's
            w[1] += Math.min(c.observed(), CLIP * r + CLIP_POINTS);
            if (w[1] >= WINDOW_POINTS && w[0] > 0) {
                var old = scales.getOrDefault(f, 1.0);
                scales.put(f, Math.clamp(old + ALPHA * (w[1] / w[0] - old), 0.3, 3.0));
                w[0] = w[1] = 0;
            }
            ch.predicted += r * scale(c.model());
            ch.observed += c.observed();
        }
        if (c.replyChars() != null) { ch.reply = (ch.reply * ch.replies + c.replyChars()) / (ch.replies + 1); ch.replies++; }
    }
}
