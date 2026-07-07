package com.adpilot.modules.apisync.connector;

import com.adpilot.modules.apisync.model.ConnectionContext;
import com.adpilot.modules.apisync.model.ExternalPage;
import com.adpilot.modules.apisync.model.PageCursor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the shared normalization helpers used by both REST
 * connectors: tolerant timestamp parsing (incremental correctness, Req 1.1.6)
 * and raw-field preservation for downstream mapping (Req 1.1.1).
 */
class AbstractRestDataConnectorTest {

    /** Minimal concrete connector exposing the protected helpers for testing. */
    private static final class TestConnector extends AbstractRestDataConnector {
        TestConnector() {
            super(new ObjectMapper(), new com.adpilot.common.config.HttpClientFactory(10, 30, 60));
        }

        @Override public String platform() { return "test"; }

        @Override public ExternalPage pullOrders(ConnectionContext ctx, Instant since, PageCursor cursor) {
            return ExternalPage.empty();
        }

        @Override public ExternalPage pullProducts(ConnectionContext ctx, Instant since, PageCursor cursor) {
            return ExternalPage.empty();
        }
    }

    private final TestConnector connector = new TestConnector();

    @Test
    void parseInstantHandlesShopifyOffsetTimestamp() {
        Instant result = connector.parseInstant("2024-01-02T03:04:05-05:00");

        assertThat(result).isEqualTo(Instant.parse("2024-01-02T08:04:05Z"));
    }

    @Test
    void parseInstantTreatsWooCommerceGmtLocalTimeAsUtc() {
        Instant result = connector.parseInstant("2024-01-02T03:04:05");

        assertThat(result).isEqualTo(Instant.parse("2024-01-02T03:04:05Z"));
    }

    @Test
    void parseInstantHandlesSpaceSeparatedTimestampAsUtc() {
        Instant result = connector.parseInstant("2024-01-02 03:04:05");

        assertThat(result).isEqualTo(Instant.parse("2024-01-02T03:04:05Z"));
    }

    @Test
    void parseInstantReturnsNullForMissingOrUnparseableValues() {
        assertThat(connector.parseInstant(null)).isNull();
        assertThat(connector.parseInstant("")).isNull();
        assertThat(connector.parseInstant("not-a-date")).isNull();
    }

    @Test
    void toIsoFormatsInstantAsUtc() {
        assertThat(AbstractRestDataConnector.toIso(Instant.parse("2024-01-02T03:04:05Z")))
                .isEqualTo("2024-01-02T03:04:05Z");
    }

    @Test
    void toFieldMapPreservesRawValuesAndTypes() throws Exception {
        JsonNode node = new ObjectMapper().readTree(
                "{\"id\":123,\"status\":\"active\",\"total\":\"19.99\",\"flag\":true}");

        Map<String, Object> fields = connector.toFieldMap(node);

        assertThat(fields).containsEntry("id", 123)
                .containsEntry("status", "active")
                .containsEntry("total", "19.99")
                .containsEntry("flag", true);
    }

    @Test
    void textReturnsNullForMissingOrNullFields() throws Exception {
        JsonNode node = new ObjectMapper().readTree("{\"a\":\"x\",\"b\":null}");

        assertThat(AbstractRestDataConnector.text(node, "a")).isEqualTo("x");
        assertThat(AbstractRestDataConnector.text(node, "b")).isNull();
        assertThat(AbstractRestDataConnector.text(node, "missing")).isNull();
    }
}
