package com.adpilot.modules.apisync.connector;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the AWS Signature Version 4 signer used to sign Amazon SP-API
 * requests (Req 8.1.2). These verify the deterministic structure of the signing
 * headers and the canonical empty-payload hash without any network call.
 */
class AwsV4SignerTest {

    /** SHA-256 of the empty string — the canonical hash for a GET with no body. */
    private static final String EMPTY_SHA256 =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private static final Instant AT = Instant.parse("2015-08-30T12:36:00Z");

    @Test
    void signProducesExpectedHeaderSetAndCredentialScope() {
        URI uri = URI.create("https://sellingpartnerapi-na.amazon.com/orders/v0/orders?MarketplaceIds=ATVPDKIKX0DER");

        Map<String, String> headers = AwsV4Signer.sign(
                "GET", uri, "execute-api", "us-east-1",
                "AKIDEXAMPLE", "wJalrXUtnFEMI/K7MDENG+bPxRFiCYEXAMPLEKEY",
                null, new byte[0], AT);

        assertThat(headers).containsKeys("Authorization", "X-Amz-Date", "x-amz-content-sha256");
        assertThat(headers).doesNotContainKey("X-Amz-Security-Token");
        assertThat(headers.get("X-Amz-Date")).isEqualTo("20150830T123600Z");
        assertThat(headers.get("x-amz-content-sha256")).isEqualTo(EMPTY_SHA256);
        assertThat(headers.get("Authorization"))
                .startsWith("AWS4-HMAC-SHA256 ")
                .contains("Credential=AKIDEXAMPLE/20150830/us-east-1/execute-api/aws4_request")
                // Headers are signed in lowercased, sorted order.
                .contains("SignedHeaders=host;x-amz-content-sha256;x-amz-date")
                .contains("Signature=");
    }

    @Test
    void signatureIsDeterministicForIdenticalInputs() {
        URI uri = URI.create("https://sellingpartnerapi-na.amazon.com/fba/inventory/v1/summaries?marketplaceIds=ATVPDKIKX0DER");

        Map<String, String> a = AwsV4Signer.sign("GET", uri, "execute-api", "us-east-1",
                "AKID", "secret", null, new byte[0], AT);
        Map<String, String> b = AwsV4Signer.sign("GET", uri, "execute-api", "us-east-1",
                "AKID", "secret", null, new byte[0], AT);

        assertThat(a.get("Authorization")).isEqualTo(b.get("Authorization"));
        // The signature is a 64-char lowercase hex SHA-256 HMAC.
        String signature = a.get("Authorization").replaceAll(".*Signature=", "");
        assertThat(signature).matches("[0-9a-f]{64}");
    }

    @Test
    void sessionTokenIsSignedAndReturned() {
        URI uri = URI.create("https://sellingpartnerapi-eu.amazon.com/orders/v0/orders");

        Map<String, String> headers = AwsV4Signer.sign("GET", uri, "execute-api", "eu-west-1",
                "AKID", "secret", "SESSIONTOKEN", new byte[0], AT);

        assertThat(headers.get("X-Amz-Security-Token")).isEqualTo("SESSIONTOKEN");
        assertThat(headers.get("Authorization"))
                .contains("SignedHeaders=host;x-amz-content-sha256;x-amz-date;x-amz-security-token");
    }

    @Test
    void differentSecretsProduceDifferentSignatures() {
        URI uri = URI.create("https://sellingpartnerapi-na.amazon.com/orders/v0/orders");

        String sigA = AwsV4Signer.sign("GET", uri, "execute-api", "us-east-1",
                "AKID", "secretA", null, new byte[0], AT).get("Authorization");
        String sigB = AwsV4Signer.sign("GET", uri, "execute-api", "us-east-1",
                "AKID", "secretB", null, new byte[0], AT).get("Authorization");

        assertThat(sigA).isNotEqualTo(sigB);
    }
}
