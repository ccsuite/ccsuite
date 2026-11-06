package ccsuite.webclient;

import module java.base;
import okhttp3.*;
import org.junit.Test;

import static org.junit.Assert.*;

/** The web wants fresh session cookies for every message of a chat, not only at its start (follow-ups of a reused chat were refused with HTTP 429 otherwise). */
public class SessionCookiesTest {
    final List<String> wire = new ArrayList<>();
    final List<String> cookiesSent = new ArrayList<>();
    final AtomicInteger bootstraps = new AtomicInteger();

    OkHttpClient client() {
        Interceptor web = chain -> {
            var request = chain.request();
            wire.add(request.method() + " " + request.url().encodedPath());
            cookiesSent.add(String.valueOf(request.header("Cookie")));
            var response = new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("ok").body(ResponseBody.create("{}", MediaType.get("application/json")));
            if (request.url().encodedPath().equals("/api/bootstrap")) response.header("Set-Cookie", "__cf_bm=cookie" + bootstraps.incrementAndGet() + "; Path=/");
            return response.build();
        };
        return new OkHttpClient.Builder().addInterceptor(new SessionCookies()).addInterceptor(web).build();
    }

    void post(OkHttpClient client, String path) throws IOException {
        var request = new Request.Builder().url("https://claude.example.org" + path).header("Cookie", "sessionKey=k1").post(RequestBody.create("{}", MediaType.get("application/json"))).build();
        client.newCall(request).execute().close();
    }

    @Test
    public void everyMessageAfterTheFirstOfAChatGetsAFreshBootstrap() throws IOException {
        var client = client();
        post(client, "/api/organizations/o/chat_conversations");
        post(client, "/api/organizations/o/chat_conversations/c/completion");
        post(client, "/api/organizations/o/chat_conversations/c/completion");
        post(client, "/api/organizations/o/chat_conversations/c/completion");
        assertEquals(List.of(
            "GET /api/bootstrap", "POST /api/organizations/o/chat_conversations",
            "POST /api/organizations/o/chat_conversations/c/completion",
            "GET /api/bootstrap", "POST /api/organizations/o/chat_conversations/c/completion",
            "GET /api/bootstrap", "POST /api/organizations/o/chat_conversations/c/completion"), wire);
        assertTrue("the follow-up carries the newest cookie", cookiesSent.get(4).contains("__cf_bm=cookie2"));
        assertTrue(cookiesSent.get(6).contains("__cf_bm=cookie3"));
    }

    @Test
    public void theFirstMessageOfAChatDoesNotBootstrapTwice() throws IOException {
        var client = client();
        post(client, "/api/organizations/o/chat_conversations");
        post(client, "/api/organizations/o/chat_conversations/c/completion");
        assertEquals(1, bootstraps.get());
    }
}
