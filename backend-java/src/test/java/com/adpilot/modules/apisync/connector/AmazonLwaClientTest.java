package com.adpilot.modules.apisync.connector;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the LWA re-auth classification used to decide when an expired
 * or invalid Amazon token must surface as a re-authorization requirement
 * (Req 8.1.5).
 */
class AmazonLwaClientTest {

    @Test
    void invalidGrantIsReauthRegardlessOfStatus() {
        assertThat(AmazonLwaClient.isReauth(200, "invalid_grant")).isTrue();
        assertThat(AmazonLwaClient.isReauth(400, "invalid_grant")).isTrue();
    }

    @Test
    void invalidTokenAndInvalidClientAreReauth() {
        assertThat(AmazonLwaClient.isReauth(401, "invalid_token")).isTrue();
        assertThat(AmazonLwaClient.isReauth(400, "invalid_client")).isTrue();
    }

    @Test
    void clientErrorStatusesAreReauthEvenWithoutErrorCode() {
        assertThat(AmazonLwaClient.isReauth(400, null)).isTrue();
        assertThat(AmazonLwaClient.isReauth(401, null)).isTrue();
        assertThat(AmazonLwaClient.isReauth(403, null)).isTrue();
    }

    @Test
    void serverErrorsAndSuccessWithoutErrorAreNotReauth() {
        assertThat(AmazonLwaClient.isReauth(500, null)).isFalse();
        assertThat(AmazonLwaClient.isReauth(503, null)).isFalse();
        assertThat(AmazonLwaClient.isReauth(200, null)).isFalse();
    }
}
