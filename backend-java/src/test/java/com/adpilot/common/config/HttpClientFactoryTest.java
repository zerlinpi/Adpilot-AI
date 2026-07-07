package com.adpilot.common.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link HttpClientFactory} (reliability fix H1): every request
 * factory it produces carries bounded, config-driven connect/read timeouts so a
 * slow or hung dependency can never pin the calling thread indefinitely. No
 * timeout is ever left at 0 (infinite).
 */
@DisplayName("HttpClientFactory — bounded, config-driven timeouts")
class HttpClientFactoryTest {

    private static final int CONNECT = 10;
    private static final int READ = 30;
    private static final int LONG_READ = 60;

    private final HttpClientFactory factory = new HttpClientFactory(CONNECT, READ, LONG_READ);

    @Test
    @DisplayName("exposes the configured timeout values")
    void exposesConfiguredValues() {
        assertThat(factory.getConnectTimeoutSeconds()).isEqualTo(CONNECT);
        assertThat(factory.getReadTimeoutSeconds()).isEqualTo(READ);
        assertThat(factory.getLongReadTimeoutSeconds()).isEqualTo(LONG_READ);
    }

    @Test
    @DisplayName("timeoutRequestFactory applies the default connect + read timeouts (never infinite)")
    void defaultRequestFactoryTimeouts() {
        ClientHttpRequestFactory f = factory.timeoutRequestFactory();
        assertThat(f).isInstanceOf(SimpleClientHttpRequestFactory.class);
        assertThat(connectTimeoutMillis(f)).isEqualTo(10_000);
        assertThat(readTimeoutMillis(f)).isEqualTo(30_000);
        assertThat(connectTimeoutMillis(f)).isPositive();
        assertThat(readTimeoutMillis(f)).isPositive();
    }

    @Test
    @DisplayName("longReadRequestFactory keeps the connect timeout but uses the longer (still bounded) read timeout")
    void longReadRequestFactoryTimeouts() {
        ClientHttpRequestFactory f = factory.longReadRequestFactory();
        assertThat(f).isInstanceOf(SimpleClientHttpRequestFactory.class);
        assertThat(connectTimeoutMillis(f)).isEqualTo(10_000);
        assertThat(readTimeoutMillis(f)).isEqualTo(60_000);
    }

    @Test
    @DisplayName("builders and clients are non-null and reusable")
    void buildersProduceClients() {
        assertThat(factory.timeoutRestClientBuilder()).isNotNull();
        assertThat(factory.longReadRestClientBuilder()).isNotNull();
        assertThat(factory.timeoutRestClient()).isNotNull();
        assertThat(factory.longReadRestClient()).isNotNull();
    }

    private static int connectTimeoutMillis(ClientHttpRequestFactory f) {
        Object v = ReflectionTestUtils.getField(f, "connectTimeout");
        return ((Number) v).intValue();
    }

    private static int readTimeoutMillis(ClientHttpRequestFactory f) {
        Object v = ReflectionTestUtils.getField(f, "readTimeout");
        return ((Number) v).intValue();
    }
}
