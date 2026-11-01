package ccsuite.ccgate;

import module java.base;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;

/**
 * The few changes the gate makes to a request body: the identity line in front when the caller sent none (the
 * subscription serves models above Haiku only with it), the grant statement when the grant has a purpose, and the
 * serving account's id in metadata.user_id. Everything else passes untouched.
 */
public final class BodyRewrite {

    static final ObjectMapper JSON = new ObjectMapper();
    static final String CLAUDE_CODE = "You are Claude Code, Anthropic's official CLI for Claude.",
            AGENT_SDK = "You are a Claude agent, built on Anthropic's Claude Agent SDK.";
    static final String BILLING = "x-anthropic-billing-header:";

    public static ObjectNode parse(byte[] body) throws IOException {
        var n = JSON.readTree(body);
        if (!(n instanceof ObjectNode o)) throw new IOException("request body is not a JSON object");
        return o;
    }

    public static void prepare(ObjectNode req, Grant grant) {
        var sys = systemArray(req);
        if (!hasIdentity(sys)) sys.insert(0, text(CLAUDE_CODE));
        if (grant != null && grant.purpose() != null && !grant.purpose().isBlank())
            sys.insert(identityIndex(sys) + 1, text(statement(grant)));
        req.set("system", sys);
    }

    public static byte[] forAccount(ObjectNode req, String accountUuid) throws IOException {
        if (accountUuid == null || !req.path("metadata").path("user_id").isTextual()) return JSON.writeValueAsBytes(req);
        var copy = req.deepCopy();
        var meta = (ObjectNode) copy.path("metadata");
        try {
            var uid = JSON.readTree(meta.path("user_id").asText());
            if (uid instanceof ObjectNode o && o.has("account_uuid")) {
                o.put("account_uuid", accountUuid);
                meta.put("user_id", JSON.writeValueAsString(o));
            }
        } catch (IOException notJson) {
            // an older Claude Code sends a plain string here; leave it as it is
        }
        return JSON.writeValueAsBytes(copy);
    }

    static ArrayNode systemArray(ObjectNode req) {
        var s = req.get("system");
        if (s instanceof ArrayNode a) return a;
        var a = JSON.createArrayNode();
        if (s != null && s.isTextual() && !s.asText().isEmpty()) a.add(text(s.asText()));
        return a;
    }

    static boolean hasIdentity(ArrayNode sys) {
        return identityIndex(sys) >= 0;
    }

    /** Index of the identity block, allowing for the billing block Claude Code puts before it. */
    static int identityIndex(ArrayNode sys) {
        for (var i = 0; i < Math.min(2, sys.size()); i++) {
            var t = sys.get(i).path("text").asText("");
            if (t.equals(CLAUDE_CODE) || t.equals(AGENT_SDK)) return i;
            if (!t.startsWith(BILLING)) return -1;
        }
        return -1;
    }

    static ObjectNode text(String s) {
        return JSON.createObjectNode().put("type", "text").put("text", s);
    }

    static String statement(Grant g) {
        return "This session runs under a ccgate grant named '" + g.id() + "' for this purpose: " + g.purpose().strip()
                + ". Models allowed: " + String.join(", ", g.models()) + "."
                + (g.expires() == null ? "" : " Valid until " + g.expires() + ".")
                + " Keep the work within that purpose. To ask for more time, models or budget, the user asks the provider who issued the grant.";
    }

    private BodyRewrite() { }
}
