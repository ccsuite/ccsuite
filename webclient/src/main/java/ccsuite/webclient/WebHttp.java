package ccsuite.webclient;

import module java.base;
import cdpai.curlimpersonate.Http;
import cdpai.curlimpersonate.okhttp.CurlInterceptor;
import okhttp3.*;

public final class WebHttp {
    public static final String TRANSPORT = "ccsuite.webtransport";
    static volatile boolean warned;

    public static OkHttpClient client(Interceptor... before) {
        var b = new OkHttpClient.Builder().connectTimeout(30, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true).followSslRedirects(true);
        for (var i : before) if (i != null) b.addInterceptor(i);
        if (curl()) b.addInterceptor(CurlInterceptor.chrome152());
        return b.build();
    }

    static boolean curl() {
        if ("okhttp".equalsIgnoreCase(System.getProperty(TRANSPORT))) return false;
        var problem = Http.problem();
        if (problem != null && !warned) {
            warned = true;
            System.err.println("ccsuite webclient: curl-impersonate unavailable, using plain OkHttp: " + problem);
        }
        return problem == null;
    }

    private WebHttp() { }
}
