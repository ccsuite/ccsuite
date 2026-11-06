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
        if (curl()) b.addInterceptor(new SessionCookies()).addInterceptor(new FetchStyleHeaders()).addInterceptor(new CurlInterceptor(new Http(Http.CHROME_152, curlTimeoutSeconds()), false));
        return b.build();
    }

    /**
     * The total time curl may take for one request. {@code CurlInterceptor.chrome152()} caps it at 60 s, which cuts a long streamed answer (a model writing files
     * takes minutes) while the server goes on generating and charging the account (found 2026-11-06: two free-account runs lost to it). Default 900 s,
     * {@code -Dccsuite.curlTimeoutSeconds=} to change. WORKAROUND for cdpai curlimpersonate PRP 02 (a factory with a timeout, or an idle limit): delete it when that version arrives.
     */
    static int curlTimeoutSeconds() {
        return Integer.getInteger("ccsuite.curlTimeoutSeconds", 900);
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
