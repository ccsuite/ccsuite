package ccsuite.webclient;

import module java.base;
import okhttp3.*;

final class FetchStyleHeaders implements Interceptor {
    @Override
    public Response intercept(Chain chain) throws IOException {
        var request = chain.request();
        var builder = request.newBuilder()
            .header("Sec-Fetch-Dest", "empty")
            .header("Sec-Fetch-Mode", "cors")
            .header("Sec-Fetch-Site", sameOrigin(request) ? "same-origin" : "cross-site")
            .header("Upgrade-Insecure-Requests", "")
            .header("Sec-Fetch-User", "")
            .header("Cache-Control", "");
        return chain.proceed(builder.build());
    }

    static boolean sameOrigin(Request request) {
        var origin = request.header("Origin");
        var url = origin == null ? null : HttpUrl.parse(origin);
        return url != null && url.host().equals(request.url().host());
    }
}
