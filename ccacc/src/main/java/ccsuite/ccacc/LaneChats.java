package ccsuite.ccacc;

import module java.base;

/**
 * PRP 88: what the free lane knows of each chat it holds, for the terms cost model: what the chat holds on the web (the prompts sent into it and its replies), when it was last called,
 * and its last reply (Claude Code sends that reply back inside the next request, but the proxy does not send it to the web again, so it is taken off the new part). Lost with the lane, as the
 * proxy's chats are; keyed like {@link FreeLane#started}.
 */
final class LaneChats {

    /** The smallest prompt a continuation builds (a short message measured 264 characters: the proxy's frame around it). */
    static final double MIN_CONTINUATION_PROMPT = 264;

    record State(double held, Instant last, long lastReply) {}

    private final Map<String, State> chats = Collections.synchronizedMap(new LinkedHashMap<>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String, State> e) { return size() > 500; }
    });

    State get(String key) { return key == null ? null : chats.get(key); }

    /** The chats called since this moment (sessions in use: the rate probe leaves their accounts alone). */
    Set<String> calledSince(Instant since) {
        synchronized (chats) { return chats.entrySet().stream().filter(e -> e.getValue().last().isAfter(since)).map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet()); }
    }

    /** The model's input for a call: a new chat sends its whole body (times the prompt-per-body ratio), a continued one what is new minus the reply the web already holds. Null when a continued chat is not known here. */
    FreeCostModel.Input input(String key, String account, String session, String model, String cache, long chars, double promptPerBody, Instant now) {
        if ("new".equals(cache)) return new FreeCostModel.Input(model, cache, chars * promptPerBody, 0, null, account == null ? null : FreeCostModel.chatOf(account, session));
        var s = get(key);
        if (s == null) return null;
        var prompt = Math.max(MIN_CONTINUATION_PROMPT, chars - s.lastReply());
        return new FreeCostModel.Input(model, cache, prompt, s.held(), Duration.between(s.last(), now).toSeconds(), account == null ? null : FreeCostModel.chatOf(account, session));
    }

    /** A served call: the chat now also holds this prompt and this reply. */
    void served(String key, String cache, double promptChars, long replyChars, Instant now) {
        if (key == null) return;
        var s = "new".equals(cache) ? null : get(key);
        chats.put(key, new State((s == null ? 0 : s.held()) + promptChars + replyChars, now, replyChars));
    }
}
