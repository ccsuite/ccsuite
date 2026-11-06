package ccsuite.webclient;

import module java.base;
import okhttp3.*;

final class SessionCookies implements Interceptor {
    static final Duration MAX_AGE = Duration.ofMinutes(20);
    static final String BOOTSTRAP = "/api/bootstrap";

    /** {@code messages}: how many completions went out with this jar; a second message of a chat needs a fresh bootstrap (PRP 74, see pitfalls). */
    record Jar(Map<String, String> cookies, Instant taken, AtomicInteger messages) { }

    final Map<String, Jar> jars = new ConcurrentHashMap<>();

    @Override
    public Response intercept(Chain chain) throws IOException {
        var request = chain.request();
        var key = sessionKey(request);
        if (key == null || !request.url().encodedPath().startsWith("/api/") || request.url().encodedPath().equals(BOOTSTRAP)) return chain.proceed(request);
        var jar = jars.get(key);
        var completion = isCompletion(request);
        if (jar == null || startsChat(request) || (completion && jar.messages().get() > 0) || jar.taken().plus(MAX_AGE).isBefore(Instant.now())) jar = refresh(chain, request, key);
        if (completion) jar.messages().incrementAndGet();
        var response = chain.proceed(withCookies(request, jar));
        absorb(key, jar, response);
        return response;
    }

    Jar refresh(Chain chain, Request request, String key) throws IOException {
        var origin = request.url().scheme() + "://" + request.url().host();
        var bootstrap = new Request.Builder().url(origin + BOOTSTRAP).header("Cookie", "sessionKey=" + key)
            .header("Accept", "*/*").header("Origin", origin).header("Referer", origin + "/new").build();
        var cookies = new LinkedHashMap<String, String>();
        try (var response = chain.proceed(bootstrap)) {
            collect(response, cookies);
        }
        var jar = new Jar(cookies, Instant.now(), new AtomicInteger());
        jars.put(key, jar);
        return jar;
    }

    void absorb(String key, Jar jar, Response response) {
        var cookies = new LinkedHashMap<>(jar.cookies());
        collect(response, cookies);
        jars.put(key, new Jar(cookies, jar.taken(), jar.messages()));
    }

    static void collect(Response response, Map<String, String> into) {
        for (var setCookie : response.headers("Set-Cookie")) {
            var pair = setCookie.split(";", 2)[0].split("=", 2);
            if (pair.length == 2 && !pair[0].equals("sessionKey")) into.put(pair[0], pair[1]);
        }
    }

    static Request withCookies(Request request, Jar jar) {
        var extra = jar.cookies().entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining("; "));
        return extra.isEmpty() ? request : request.newBuilder().header("Cookie", request.header("Cookie") + "; " + extra).build();
    }

    static boolean isCompletion(Request request) {
        return request.method().equals("POST") && request.url().encodedPath().endsWith("/completion");
    }

    static boolean startsChat(Request request) {
        return request.method().equals("POST") && request.url().encodedPath().endsWith("/chat_conversations");
    }

    static String sessionKey(Request request) {
        var cookie = request.header("Cookie");
        if (cookie == null) return null;
        for (var part : cookie.split(";\\s*")) if (part.startsWith("sessionKey=")) return part.substring("sessionKey=".length());
        return null;
    }
}
