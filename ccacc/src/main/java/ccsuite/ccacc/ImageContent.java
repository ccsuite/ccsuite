package ccsuite.ccacc;

import module java.base;
import ccsuite.webproxy.ImageAttachments;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * PRP 96: the free lane sends an image of the message being answered to the web chat as an uploaded attachment ({@link ImageAttachments}). An image it cannot carry (not base64
 * data, a type the web does not read, too large) is refused in the API's error shape instead of being answered as if no image had come, so a consumer sees the failure and falls
 * back. The size of a request, for the cost estimate, leaves the base64 out: a picture costs the web about a thousand tokens, not what its text would.
 */
final class ImageContent {

    /** What a picture costs the web chat in characters of the estimator's one ratio (about 1,500 tokens). */
    static final int PICTURE_CHARS = 6_000;

    /** Why the last message holds an image the lane cannot carry, or null. */
    static String problem(JsonNode request) {
        return ImageAttachments.problemInLastMessage(request.path("messages"));
    }

    /** The refusal text the guard answers with (an invalid_request_error body). */
    static String refusal(String problem) {
        return FreeLane.JSON.createObjectNode().put("type", "error").set("error", FreeLane.JSON.createObjectNode().put("type", "invalid_request_error")
                .put("message", "fclaude: the free lane cannot carry this image: " + problem + ". It takes base64 jpeg, png, gif or webp images; send it to a lane that has a vision model (pclaude, or the API), or leave the image out.")).toString();
    }

    /** The request's size for the cost estimate: its bytes, with each base64 image counted as {@link #PICTURE_CHARS}. */
    static int sizeOf(byte[] body) {
        if (!containsImage(body)) return body.length;
        try {
            var data = 0;
            var all = FreeLane.JSON.readTree(body).path("messages");
            for (var message : all) data += dataIn(message.path("content"));
            return Math.max(0, body.length - data);
        } catch (Exception e) {
            return body.length;
        }
    }

    /** A cheap look before parsing: bodies without an image block are by far the most. */
    private static boolean containsImage(byte[] body) {
        return new String(body, StandardCharsets.ISO_8859_1).contains("\"image\"");
    }

    private static int dataIn(JsonNode content) {
        var total = 0;
        if (!content.isArray()) return 0;
        for (var block : content) {
            switch (block.path("type").asText()) {
                case "image" -> total += Math.max(0, block.path("source").path("data").asText("").length() - PICTURE_CHARS);
                case "tool_result" -> total += dataIn(block.path("content"));
                default -> { }
            }
        }
        return total;
    }

    private ImageContent() {}
}
