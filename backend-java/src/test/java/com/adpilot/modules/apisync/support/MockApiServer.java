package com.adpilot.modules.apisync.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * A tiny mock platform server backed by the JDK's built-in
 * {@link com.sun.net.httpserver.HttpServer}/{@link HttpsServer}. Integration
 * tests register per-path responders and point a real connector at
 * {@link #host()}; no external test dependency (WireMock/MockWebServer) is
 * required.
 *
 * <p>The HTTPS factory exists because {@code ShopifyConnector} and
 * {@code PlatformConnector#testShopify} build {@code https://} URLs and cannot
 * be redirected to plain HTTP. WooCommerce, which builds its URL from the
 * configured {@code siteUrl}, is served over plain HTTP.</p>
 */
public final class MockApiServer implements AutoCloseable {

    /** A canned HTTP response: status code plus JSON body. */
    public record Response(int status, String body, Map<String, String> headers) {
        public static Response json(int status, String body) {
            return new Response(status, body, Map.of());
        }
    }

    /**
     * A captured inbound request, exposing the method, path, raw query, and the
     * request headers (lower-cased keys, first value) so integration tests can
     * assert on signing/auth headers attached by a connector (Req 8.1.2).
     */
    public record RecordedRequest(String method, String path, String query, Map<String, String> headers) {
        /** Header lookup is case-insensitive (HTTP header names are case-insensitive). */
        public String header(String name) {
            return headers.get(name.toLowerCase());
        }
    }

    private final HttpServer server;
    private final Map<String, Function<String, Response>> routes = new ConcurrentHashMap<>();
    private final List<String> requestLog = new CopyOnWriteArrayList<>();
    private final List<RecordedRequest> recordedRequests = new CopyOnWriteArrayList<>();

    private MockApiServer(HttpServer server) {
        this.server = server;
        server.createContext("/", this::dispatch);
        server.setExecutor(Executors.newSingleThreadExecutor());
        server.start();
    }

    /** Start a plain-HTTP mock server bound to localhost on an ephemeral port. */
    public static MockApiServer http() throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress(InetAddress.getByName("localhost"), 0), 0);
        return new MockApiServer(s);
    }

    /** Start an HTTPS mock server using the supplied server-side {@link SSLContext}. */
    public static MockApiServer https(SSLContext sslContext) throws IOException {
        HttpsServer s = HttpsServer.create(new InetSocketAddress(InetAddress.getByName("localhost"), 0), 0);
        s.setHttpsConfigurator(new HttpsConfigurator(sslContext));
        return new MockApiServer(s);
    }

    /** Register a responder for an exact request path; the responder receives the raw query string. */
    public MockApiServer route(String path, Function<String, Response> responder) {
        routes.put(path, responder);
        return this;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    /** Host:port suitable for building a connection URL ({@code localhost} matches the test cert SAN). */
    public String host() {
        return "localhost:" + port();
    }

    /** Every request line seen, as {@code path?query}, for assertions. */
    public List<String> requests() {
        return requestLog;
    }

    /** Every inbound request captured with its method and headers, for assertions. */
    public List<RecordedRequest> recordedRequests() {
        return recordedRequests;
    }

    /** The most recent recorded request whose path equals {@code path}, or {@code null}. */
    public RecordedRequest lastRequestTo(String path) {
        RecordedRequest match = null;
        for (RecordedRequest req : recordedRequests) {
            if (req.path().equals(path)) {
                match = req;
            }
        }
        return match;
    }

    private void dispatch(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String query = exchange.getRequestURI().getQuery();
        requestLog.add(path + (query != null ? "?" + query : ""));

        Map<String, String> headers = new java.util.HashMap<>();
        exchange.getRequestHeaders().forEach((name, values) -> {
            if (name != null && !values.isEmpty()) {
                headers.put(name.toLowerCase(), values.get(0));
            }
        });
        recordedRequests.add(new RecordedRequest(
                exchange.getRequestMethod(), path, query, Map.copyOf(headers)));

        Function<String, Response> responder = routes.get(path);
        Response response = responder != null ? responder.apply(query) : Response.json(404, "{}");

        byte[] body = response.body() == null ? new byte[0] : response.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        response.headers().forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
        exchange.sendResponseHeaders(response.status(), body.length == 0 ? -1 : body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
