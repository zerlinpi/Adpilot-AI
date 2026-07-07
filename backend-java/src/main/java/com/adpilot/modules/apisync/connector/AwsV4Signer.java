package com.adpilot.modules.apisync.connector;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.TreeMap;

/**
 * Implements the AWS Signature Version 4 request-signing scheme used by the
 * Amazon Selling Partner API (Req 8.1.2). Given a request's method, URI,
 * headers, and payload, it produces the {@code Authorization}, {@code X-Amz-Date},
 * and {@code x-amz-content-sha256} headers (plus {@code X-Amz-Security-Token}
 * when a session token is supplied) that authenticate the request.
 *
 * <p>This is the canonical, deterministic SigV4 algorithm: build a canonical
 * request, derive a date-/region-/service-scoped signing key, and HMAC-SHA256
 * the string-to-sign. It is stateless and side-effect free, so it is unit
 * testable against AWS's published test vectors.</p>
 *
 * <p>SECURITY: the secret key and session token are used only to derive the
 * signature locally; they are never placed in a returned header value except
 * the session token, which AWS itself requires to be sent as
 * {@code X-Amz-Security-Token}. Nothing here logs.</p>
 */
public final class AwsV4Signer {

    private static final String ALGORITHM = "AWS4-HMAC-SHA256";
    private static final DateTimeFormatter AMZ_DATE_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    private AwsV4Signer() {
    }

    /**
     * Compute the SigV4 signing headers for a request.
     *
     * @param method       HTTP method (e.g. {@code GET})
     * @param uri          fully-formed request URI including any query string
     * @param service      AWS service name (e.g. {@code "execute-api"})
     * @param region       AWS region (e.g. {@code "us-east-1"})
     * @param accessKey     AWS access key id
     * @param secretKey     AWS secret access key
     * @param sessionToken  optional STS session token; {@code null} when absent
     * @param payload      request body bytes ({@code null}/empty for GET)
     * @param at           the signing instant (request time)
     * @return headers to add to the request to authenticate it
     */
    public static Map<String, String> sign(String method,
                                            URI uri,
                                            String service,
                                            String region,
                                            String accessKey,
                                            String secretKey,
                                            String sessionToken,
                                            byte[] payload,
                                            Instant at) {
        byte[] body = payload == null ? new byte[0] : payload;
        String amzDateTime = AMZ_DATE_TIME.format(at);
        String amzDate = AMZ_DATE.format(at);
        String host = uri.getHost();
        if (uri.getPort() != -1) {
            host = host + ":" + uri.getPort();
        }
        String payloadHash = hex(sha256(body));

        // Canonical headers must be sorted by lowercased name; include host,
        // x-amz-date, x-amz-content-sha256, and the session token when present.
        TreeMap<String, String> canonicalHeaders = new TreeMap<>();
        canonicalHeaders.put("host", host);
        canonicalHeaders.put("x-amz-content-sha256", payloadHash);
        canonicalHeaders.put("x-amz-date", amzDateTime);
        if (sessionToken != null && !sessionToken.isBlank()) {
            canonicalHeaders.put("x-amz-security-token", sessionToken);
        }

        StringBuilder canonicalHeaderBlock = new StringBuilder();
        StringBuilder signedHeaders = new StringBuilder();
        for (Map.Entry<String, String> e : canonicalHeaders.entrySet()) {
            canonicalHeaderBlock.append(e.getKey()).append(':')
                    .append(e.getValue().trim()).append('\n');
            if (signedHeaders.length() > 0) {
                signedHeaders.append(';');
            }
            signedHeaders.append(e.getKey());
        }

        String canonicalRequest = method + "\n"
                + canonicalPath(uri) + "\n"
                + canonicalQuery(uri) + "\n"
                + canonicalHeaderBlock + "\n"
                + signedHeaders + "\n"
                + payloadHash;

        String credentialScope = amzDate + "/" + region + "/" + service + "/aws4_request";
        String stringToSign = ALGORITHM + "\n"
                + amzDateTime + "\n"
                + credentialScope + "\n"
                + hex(sha256(canonicalRequest.getBytes(StandardCharsets.UTF_8)));

        byte[] signingKey = deriveSigningKey(secretKey, amzDate, region, service);
        String signature = hex(hmacSha256(signingKey, stringToSign.getBytes(StandardCharsets.UTF_8)));

        String authorization = ALGORITHM
                + " Credential=" + accessKey + "/" + credentialScope
                + ", SignedHeaders=" + signedHeaders
                + ", Signature=" + signature;

        TreeMap<String, String> out = new TreeMap<>();
        out.put("Authorization", authorization);
        out.put("X-Amz-Date", amzDateTime);
        out.put("x-amz-content-sha256", payloadHash);
        if (sessionToken != null && !sessionToken.isBlank()) {
            out.put("X-Amz-Security-Token", sessionToken);
        }
        return out;
    }

    private static String canonicalPath(URI uri) {
        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) {
            return "/";
        }
        return path;
    }

    /** Canonical query string: parameters sorted by encoded name (then value). */
    private static String canonicalQuery(URI uri) {
        String raw = uri.getRawQuery();
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        TreeMap<String, String> params = new TreeMap<>();
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            String name = eq >= 0 ? pair.substring(0, eq) : pair;
            String value = eq >= 0 ? pair.substring(eq + 1) : "";
            params.put(name, value);
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    private static byte[] deriveSigningKey(String secretKey, String amzDate, String region, String service) {
        byte[] kSecret = ("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8);
        byte[] kDate = hmacSha256(kSecret, amzDate.getBytes(StandardCharsets.UTF_8));
        byte[] kRegion = hmacSha256(kDate, region.getBytes(StandardCharsets.UTF_8));
        byte[] kService = hmacSha256(kRegion, service.getBytes(StandardCharsets.UTF_8));
        return hmacSha256(kService, "aws4_request".getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static byte[] hmacSha256(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 failed", e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
