package com.adpilot.modules.apisync.support;

import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.URI;

/**
 * Test helper that redirects a connector's outbound HTTP calls to a local
 * {@link MockApiServer}. The Amazon connectors (and the shared
 * {@code AmazonLwaClient}) build absolute {@code https://*.amazon.com} URLs from
 * hard-coded regional hosts, so an integration test cannot reach them by
 * configuring a base URL. This helper builds a {@link RestClient} whose request
 * factory rewrites every outbound URI's scheme/host/port to the mock server
 * while preserving the path and query string, then injects it into the target's
 * (package-private, final) {@code http} field via reflection.
 *
 * <p>Rewriting to plain {@code http://localhost:port} sidesteps TLS entirely:
 * the connector's chosen path (e.g. {@code /orders/v0/orders} or
 * {@code /auth/o2/token}) is preserved so the {@link MockApiServer} can route on
 * it, and any signing/auth headers the connector attaches travel unchanged and
 * are captured by {@link MockApiServer.RecordedRequest}.</p>
 */
public final class MockHttpRedirect {

    private MockHttpRedirect() {
    }

    /**
     * Build a {@link RestClient} that sends every request to {@code hostPort}
     * (e.g. {@code localhost:54321}) over plain HTTP, preserving the original
     * request's path and query string.
     */
    public static RestClient restClientTo(String hostPort) {
        ClientHttpRequestFactory delegate = new SimpleClientHttpRequestFactory();
        ClientHttpRequestFactory redirecting = new ClientHttpRequestFactory() {
            @Override
            public ClientHttpRequest createRequest(URI uri, HttpMethod httpMethod) throws IOException {
                StringBuilder pathAndQuery = new StringBuilder(
                        uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath());
                if (uri.getRawQuery() != null) {
                    pathAndQuery.append('?').append(uri.getRawQuery());
                }
                URI target = URI.create("http://" + hostPort + pathAndQuery);
                return delegate.createRequest(target, httpMethod);
            }
        };
        return RestClient.builder().requestFactory(redirecting).build();
    }

    /**
     * Point the given connector or client at the mock server by replacing its
     * internal {@code http} {@link RestClient} with one that redirects to
     * {@code hostPort}.
     */
    public static void redirect(Object target, String hostPort) {
        try {
            Field field = findField(target.getClass(), "http");
            field.setAccessible(true);
            field.set(target, restClientTo(hostPort));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "Failed to redirect HTTP client on " + target.getClass().getName(), e);
        }
    }

    private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException("No field '" + name + "' on " + type.getName() + " or its superclasses");
    }
}
