package com.adpilot.modules.advertising.hosting;

import com.adpilot.common.config.HttpClientFactory;
import com.adpilot.modules.apisync.connector.AmazonLwaClient;
import com.adpilot.modules.apisync.model.ConnectionContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Unit tests for {@link ReportLifecycleClientImpl} focused on the poll-loop
 * token handling (reliability fix M1).
 *
 * <p>The LWA access token must be resolved <em>once</em> per lifecycle and
 * reused across poll attempts rather than being refreshed on every iteration.
 * If a single attempt is rejected as unauthorized (the token expired mid-poll),
 * the client refreshes once and retries that attempt — bounded, never
 * loop-refreshing.</p>
 */
@DisplayName("ReportLifecycleClientImpl — poll token handling (M1)")
class ReportLifecycleClientImplTest {

    private static final String REPORT_ID = "report-123";
    private static final String REPORT_URL =
            "https://advertising-api.amazon.com/reporting/reports/" + REPORT_ID;

    private AmazonLwaClient lwaClient;
    private MockRestServiceServer server;
    private ReportLifecycleClientImpl client;

    private final ConnectionContext ctx = new ConnectionContext(
            UUID.randomUUID(), UUID.randomUUID(), "amazon_ads",
            Map.of("clientId", "client-1", "profileId", "profile-1"));

    @BeforeEach
    void setUp() {
        lwaClient = mock(AmazonLwaClient.class);

        // Bind a mock HTTP server to the RestClient.Builder the factory hands the
        // client, so no real network calls are made.
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();

        HttpClientFactory httpClientFactory = mock(HttpClientFactory.class);
        when(httpClientFactory.longReadRestClientBuilder()).thenReturn(builder);

        ReportLifecycleClientImpl real =
                new ReportLifecycleClientImpl(lwaClient, new ObjectMapper(), httpClientFactory);
        // Spy so the poll interval sleeps are no-ops (fast test).
        client = spy(real);
        doNothing().when(client).sleep(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    @DisplayName("Token is fetched once per lifecycle, not once per poll attempt")
    void tokenFetchedOncePerLifecycle() {
        when(lwaClient.fetchAccessToken(any(ConnectionContext.class))).thenReturn("token-1");

        // Three poll attempts: IN_PROGRESS, IN_PROGRESS, then COMPLETED.
        server.expect(requestTo(REPORT_URL))
                .andRespond(withSuccess("{\"status\":\"IN_PROGRESS\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(REPORT_URL))
                .andRespond(withSuccess("{\"status\":\"IN_PROGRESS\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(REPORT_URL))
                .andRespond(withSuccess("{\"status\":\"COMPLETED\"}", MediaType.APPLICATION_JSON));

        ReportStatus status = client.pollStatus(ctx, REPORT_ID);

        assertThat(status).isEqualTo(ReportStatus.COMPLETED);
        server.verify();
        // The token was resolved exactly once for the whole poll loop (M1),
        // not once per attempt.
        verify(lwaClient, times(1)).fetchAccessToken(any(ConnectionContext.class));
    }

    @Test
    @DisplayName("A mid-poll 401 triggers exactly one token refresh and retries the attempt")
    void unauthorizedMidPollRefreshesOnceAndRetries() {
        when(lwaClient.fetchAccessToken(any(ConnectionContext.class)))
                .thenReturn("token-1", "token-2");

        // Attempt 1: IN_PROGRESS. Attempt 2: 401 (token expired mid-poll) →
        // refresh once and retry → COMPLETED.
        server.expect(requestTo(REPORT_URL))
                .andRespond(withSuccess("{\"status\":\"IN_PROGRESS\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(REPORT_URL))
                .andRespond(withStatus(HttpStatus.UNAUTHORIZED)
                        .body("{\"error\":\"unauthorized\"}").contentType(MediaType.APPLICATION_JSON));
        server.expect(requestTo(REPORT_URL))
                .andRespond(withSuccess("{\"status\":\"COMPLETED\"}", MediaType.APPLICATION_JSON));

        ReportStatus status = client.pollStatus(ctx, REPORT_ID);

        assertThat(status).isEqualTo(ReportStatus.COMPLETED);
        server.verify();
        // One initial fetch + one refresh on the 401 = two fetches total (bounded).
        verify(lwaClient, times(2)).fetchAccessToken(any(ConnectionContext.class));
    }
}
