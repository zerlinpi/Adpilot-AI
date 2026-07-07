package com.adpilot.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * Shared factory for building {@link RestClient} instances (and the underlying
 * {@link ClientHttpRequestFactory}) with bounded connect/read timeouts.
 *
 * <p><b>Why this exists:</b> a {@code RestClient} built via
 * {@code RestClient.builder().build()} has NO connect or read timeout, so a slow
 * or hung third-party dependency can pin the calling thread indefinitely and
 * exhaust the request/worker thread pool. Every outbound HTTP client in the app
 * builds its {@code RestClient} via this factory so a bounded timeout is always
 * applied (mirroring the approach already used by {@code AiClient}).</p>
 *
 * <p>Timeouts are config-driven with safe defaults:
 * <ul>
 *   <li>{@code adpilot.http.connect-timeout-seconds} (default 10)</li>
 *   <li>{@code adpilot.http.read-timeout-seconds} (default 30)</li>
 *   <li>{@code adpilot.http.long-read-timeout-seconds} (default 60) — for calls
 *       that legitimately need a longer read (e.g. Amazon report download/poll,
 *       long-running independent-site publish).</li>
 * </ul>
 * No timeout is ever left infinite.</p>
 */
@Component
public class HttpClientFactory {

    private final int connectTimeoutSeconds;
    private final int readTimeoutSeconds;
    private final int longReadTimeoutSeconds;

    public HttpClientFactory(
            @Value("${adpilot.http.connect-timeout-seconds:10}") int connectTimeoutSeconds,
            @Value("${adpilot.http.read-timeout-seconds:30}") int readTimeoutSeconds,
            @Value("${adpilot.http.long-read-timeout-seconds:60}") int longReadTimeoutSeconds) {
        this.connectTimeoutSeconds = connectTimeoutSeconds;
        this.readTimeoutSeconds = readTimeoutSeconds;
        this.longReadTimeoutSeconds = longReadTimeoutSeconds;
    }

    public int getConnectTimeoutSeconds() {
        return connectTimeoutSeconds;
    }

    public int getReadTimeoutSeconds() {
        return readTimeoutSeconds;
    }

    public int getLongReadTimeoutSeconds() {
        return longReadTimeoutSeconds;
    }

    /** A request factory with the default bounded connect + read timeouts. */
    public ClientHttpRequestFactory timeoutRequestFactory() {
        return build(connectTimeoutSeconds, readTimeoutSeconds);
    }

    /**
     * A request factory with the default connect timeout but the longer read
     * timeout, for calls that legitimately need more time to read a response
     * (still bounded — never infinite).
     */
    public ClientHttpRequestFactory longReadRequestFactory() {
        return build(connectTimeoutSeconds, longReadTimeoutSeconds);
    }

    /** A {@link RestClient.Builder} pre-configured with the default bounded timeouts. */
    public RestClient.Builder timeoutRestClientBuilder() {
        return RestClient.builder().requestFactory(timeoutRequestFactory());
    }

    /** A {@link RestClient.Builder} pre-configured with the longer (bounded) read timeout. */
    public RestClient.Builder longReadRestClientBuilder() {
        return RestClient.builder().requestFactory(longReadRequestFactory());
    }

    /** A ready-to-use {@link RestClient} with the default bounded timeouts. */
    public RestClient timeoutRestClient() {
        return timeoutRestClientBuilder().build();
    }

    /** A ready-to-use {@link RestClient} with the longer (bounded) read timeout. */
    public RestClient longReadRestClient() {
        return longReadRestClientBuilder().build();
    }

    private static SimpleClientHttpRequestFactory build(int connectSeconds, int readSeconds) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(connectSeconds).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(readSeconds).toMillis());
        return factory;
    }
}
