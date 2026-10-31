package ccsuite.webclient;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import ccsuite.base.config.CredentialsManager;
import ccsuite.base.model.CCAPICredential;
import ccsuite.base.model.CCAPIsCredentials;
import ccsuite.base.model.UsageData;
import ccsuite.base.usage.UsageSource;
import jakarta.xml.bind.JAXBException;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * API client to fetch usage data from CCAPI endpoints
 * Uses OkHttp for HTTP requests
 */
public class WebUsageSource implements UsageSource {

    private static final String DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/100.0.4896.75 Safari/537.36";

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    /**
     * Create usage API client (stateless - credentials passed to each method)
     */
    public WebUsageSource() {
        this.objectMapper = new ObjectMapper();
        // Register Java 8 time module for OffsetDateTime support
        this.objectMapper.registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        // Disable failing on unknown properties to be more flexible with API changes
        this.objectMapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build();
    }

    /**
     * Fetch usage data for a specific organization using CCAPICredential from base module.
     * Each credential now has its own baseUrl.
     * curl command syntax:
     * curl_chrome100.bat --insecure "https://%CCAPI_BASE_URL%/api/organizations/<orgid_value>/usage" -b "sessionKey=<key_value>;"
     */
    /** The account's cookie credential is read from the shared credentials file on every call, so a rotated key is picked up. */
    @Override
    public UsageData fetchUsage(String accountId) throws IOException {
        CCAPIsCredentials all;
        try {
            all = new CredentialsManager().load();
        } catch (JAXBException e) {
            throw new IOException("cannot read credentials: " + e.getMessage(), e);
        }
        var credential = all == null ? null : all.getById(accountId);
        if (credential == null) throw new IOException("no web credential saved under id: " + accountId);
        return fetchUsage(credential);
    }

    public UsageData fetchUsage(CCAPICredential credential) throws IOException {
        // Use credential's own baseUrl (overrides the one passed to constructor)
        var credentialBaseUrl = credential.resolvedCcapiBaseUrl();

        // Validate baseUrl is not null
        if (credentialBaseUrl == null || credentialBaseUrl.trim().isEmpty()) {
            throw new IOException("ccapiBaseUrl is not set for credential: " + credential.id());
        }

        if (!credentialBaseUrl.startsWith("http://") && !credentialBaseUrl.startsWith("https://")) {
            credentialBaseUrl = "https://" + credentialBaseUrl;
        }

        var url = String.format("%s/api/organizations/%s/usage", credentialBaseUrl, credential.orgId());

        // Use credential's ua if specified, otherwise fall back to default
        var userAgent = (credential.ua() != null && !credential.ua().isEmpty())
                ? credential.ua()
                : DEFAULT_USER_AGENT;

        // Log which user-agent is being used (only in verbose mode)
        if (isVerboseMode()) {
            var uaSource = (credential.ua() != null && !credential.ua().isEmpty()) ? "custom" : "default";
            System.out.println("[DEBUG] Using " + uaSource + " User-Agent for " + credential.id() + ": " + userAgent);
        }

        var request = new Request.Builder()
                .url(url)
                .addHeader("Cookie", "sessionKey=" + credential.key())
                .addHeader("User-Agent", userAgent)
                .addHeader("Accept", "*/*")
                .addHeader("Origin", credentialBaseUrl)
                .addHeader("Referer", credentialBaseUrl + "/")
                .get()
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("Unexpected response code: " + response.code() + " for org: " + credential.orgId());
            }

            var responseBody = response.body();
            if (responseBody == null) {
                throw new IOException("Empty response body for org: " + credential.orgId());
            }

            return objectMapper.readValue(responseBody.string(), UsageData.class);
        }
    }

    /**
     * Fetch usage with retry using default backoff (base 800ms, cap 20s).
     */
    public UsageData fetchUsageWithRetry(CCAPICredential credential, int maxRetries) {
        return fetchUsageWithRetry(credential, maxRetries, 800, 20000);
    }

    /**
     * Fetch usage with exponential backoff + jitter.
     * Retries on transport errors, non-2xx responses, and empty/null usage bodies
     * (the server returns an empty body when it rate-limits/blacklists rapid bursts).
     */
    public UsageData fetchUsageWithRetry(CCAPICredential credential, int maxRetries, long baseDelayMs, long maxDelayMs) {
        var attempts = Math.max(1, maxRetries);
        IOException lastException = null;

        for (int attempt = 0; attempt < attempts; attempt++) {
            try {
                var data = fetchUsage(credential);
                if (isEmptyUsage(data)) {
                    throw new IOException("empty/null usage body (likely rate-limited) for org: " + credential.orgId());
                }
                return data;
            } catch (IOException e) {
                lastException = e;
                if (isVerboseMode()) {
                    System.err.println("[DEBUG] attempt " + (attempt + 1) + "/" + attempts + " for " + credential.id() + ": " + e.getMessage());
                }
                if (attempt < attempts - 1) {
                    backoffSleep(attempt, baseDelayMs, maxDelayMs);
                }
            }
        }

        // Build detailed error message
        var errorMsg = String.format("Failed to fetch usage after %d attempts for credential '%s'",
            attempts, credential.id());

        if (lastException != null) {
            var detailedMsg = lastException.getMessage();
            if (detailedMsg != null && detailedMsg.contains("ccapiBaseUrl")) {
                errorMsg += " - ccapiBaseUrl is missing or invalid in credentials.xml";
            } else {
                errorMsg += " - " + detailedMsg;
            }
        }

        throw new RuntimeException(errorMsg, lastException);
    }

    /**
     * A usage body is "empty" when every known window is null - this is what the
     * server returns while throttling/blacklisting, so it must be retried.
     */
    private static boolean isEmptyUsage(UsageData d) {
        return d == null || (d.fiveHour() == null && d.sevenDay() == null
                && d.sevenDayOpus() == null && d.sevenDayOauthApps() == null
                && d.sevenDaySonnet() == null && d.sevenDayCowork() == null);
    }

    /**
     * Sleep for an exponentially growing, capped, full-jittered delay.
     * delay = random in [d/2, d] where d = min(maxDelay, base * 2^attempt).
     */
    private static void backoffSleep(int attempt, long baseDelayMs, long maxDelayMs) {
        var exp = baseDelayMs << Math.min(attempt, 16);
        var capped = Math.max(1, Math.min(maxDelayMs, exp));
        var jittered = capped / 2 + java.util.concurrent.ThreadLocalRandom.current().nextLong(capped / 2 + 1);
        try {
            Thread.sleep(jittered);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Shutdown the HTTP client and release resources
     * Call close() when done to ensure immediate program exit
     */
    @Override
    public void close() {
        // Cancel all calls first
        httpClient.dispatcher().cancelAll();

        // Evict all connections
        httpClient.connectionPool().evictAll();

        // Force shutdown executor (don't wait)
        httpClient.dispatcher().executorService().shutdownNow();
    }

    /**
     * Check if verbose/debug mode is enabled
     * Controlled by system property: ccsuite.verbose
     */
    private boolean isVerboseMode() {
        return "true".equalsIgnoreCase(System.getProperty("ccsuite.verbose"));
    }
}
