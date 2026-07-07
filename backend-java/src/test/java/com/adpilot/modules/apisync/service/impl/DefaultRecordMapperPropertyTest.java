package com.adpilot.modules.apisync.service.impl;

import com.adpilot.modules.apisync.model.ExternalRecord;
import com.adpilot.modules.apisync.model.MappedRecord;
import com.adpilot.modules.apisync.model.SyncContext;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based test for {@link DefaultRecordMapper}.
 *
 * Feature: core-platform-completion, Property 1: External-record mapping
 * populates internal fields and store association.
 *
 * For any external order or product record retrieved through a connection,
 * mapping it produces an internal record whose required fields are populated
 * with correctly typed values (money as {@link BigDecimal}, timestamps as
 * {@link Instant}) and whose store id equals the store that owns the
 * originating connection.
 *
 * Validates: Requirements 1.1.3, 1.1.4, 8.1.3
 */
class DefaultRecordMapperPropertyTest {

    private final DefaultRecordMapper mapper = new DefaultRecordMapper();

    /** Generated mapping scenario: the owning store and the external record to map. */
    record Scenario(UUID storeId, ExternalRecord record) {
    }

    // Feature: core-platform-completion, Property 1: External-record mapping populates internal fields and store association
    @Property(tries = 200)
    void mappingPopulatesRequiredFieldsWithCorrectTypesAndStoreAssociation(
            @ForAll("recordScenarios") Scenario scenario) {

        UUID storeId = scenario.storeId();
        ExternalRecord record = scenario.record();

        // The SyncContext carries the store that owns the originating connection.
        SyncContext ctx = new SyncContext(
                UUID.randomUUID(), UUID.randomUUID(), storeId,
                "woocommerce", record.entityType(), false);

        MappedRecord mapped = mapper.map(ctx, record);

        // Store association (Req 1.1.4): the mapped record belongs to the context store.
        assertThat(mapped.storeId()).isEqualTo(storeId);
        assertThat(mapped.entityType()).isEqualTo(record.entityType());
        assertThat(mapped.externalId()).isEqualTo(record.externalId());

        Map<String, Object> fields = mapped.fields();

        if (DefaultRecordMapper.ENTITY_ORDER.equals(record.entityType())) {
            // Required internal order fields are populated with correct types (Req 1.1.3, 8.1.3).
            assertThat(fields.get(DefaultRecordMapper.F_EXTERNAL_ORDER_ID))
                    .isInstanceOf(String.class)
                    .isEqualTo(record.externalId());
            assertThat(fields.get(DefaultRecordMapper.F_ORDER_STATUS))
                    .isInstanceOf(String.class)
                    .isEqualTo(record.status());
            assertThat(fields.get(DefaultRecordMapper.F_TOTAL_AMOUNT))
                    .isNotNull()
                    .isInstanceOf(BigDecimal.class);
            assertThat(fields.get(DefaultRecordMapper.F_ORDER_DATE))
                    .isNotNull()
                    .isInstanceOf(Instant.class);
        } else {
            // Required internal product fields are populated with correct types (Req 1.1.3, 8.1.3).
            assertThat(fields.get(DefaultRecordMapper.F_EXTERNAL_PRODUCT_ID))
                    .isInstanceOf(String.class)
                    .isEqualTo(record.externalId());
            assertThat(fields.get(DefaultRecordMapper.F_SKU))
                    .isNotNull()
                    .isInstanceOf(String.class);
            assertThat(fields.get(DefaultRecordMapper.F_TITLE))
                    .isNotNull()
                    .isInstanceOf(String.class);
            assertThat(fields.get(DefaultRecordMapper.F_PRICE))
                    .isNotNull()
                    .isInstanceOf(BigDecimal.class);
        }
    }

    // --- generators -----------------------------------------------------------

    @Provide
    Arbitrary<Scenario> recordScenarios() {
        return Arbitraries.oneOf(orderScenarios(), productScenarios());
    }

    private Arbitrary<Scenario> orderScenarios() {
        return Combinators.combine(
                uuids(), nonBlankStrings(), nonBlankStrings(),
                moneyValues(), timestampValues(), instants(), currencies())
                .as((storeId, externalId, status, totalAmount, orderDate, changedAt, currency) -> {
                    Map<String, Object> source = new HashMap<>();
                    source.put("totalAmount", totalAmount);
                    source.put("orderDate", orderDate);
                    source.put("currency", currency);
                    ExternalRecord record = new ExternalRecord(
                            externalId, DefaultRecordMapper.ENTITY_ORDER, changedAt, status, source);
                    return new Scenario(storeId, record);
                });
    }

    private Arbitrary<Scenario> productScenarios() {
        return Combinators.combine(
                uuids(), nonBlankStrings(), nonBlankStrings(),
                moneyValues(), nonBlankStrings(), nonBlankStrings(), currencies())
                .as((storeId, externalId, status, price, sku, title, currency) -> {
                    Map<String, Object> source = new HashMap<>();
                    source.put("sku", sku);
                    source.put("title", title);
                    source.put("price", price);
                    source.put("currency", currency);
                    ExternalRecord record = new ExternalRecord(
                            externalId, DefaultRecordMapper.ENTITY_PRODUCT, instantNow(), status, source);
                    return new Scenario(storeId, record);
                });
    }

    private Arbitrary<UUID> uuids() {
        return Combinators.combine(Arbitraries.longs(), Arbitraries.longs()).as(UUID::new);
    }

    /** Non-blank tokens with no whitespace, so the mapper's trim is a no-op. */
    private Arbitrary<String> nonBlankStrings() {
        return Arbitraries.strings().withCharRange('a', 'z').numeric()
                .ofMinLength(1).ofMaxLength(24);
    }

    private Arbitrary<String> currencies() {
        return Arbitraries.of("USD", "EUR", "GBP", "JPY", "CAD");
    }

    /** Money amounts (0.00 .. 1,000,000.00) encoded in one of several wire forms. */
    private Arbitrary<Object> moneyValues() {
        Arbitrary<BigDecimal> amounts =
                Arbitraries.longs().between(0L, 100_000_000L).map(cents -> BigDecimal.valueOf(cents, 2));
        return amounts.flatMap(amount ->
                Arbitraries.of("decimal", "string", "double", "long").map(kind -> switch (kind) {
                    case "decimal" -> amount;
                    case "string" -> amount.toPlainString();
                    case "long" -> amount.longValue();
                    default -> amount.doubleValue();
                }));
    }

    private Arbitrary<Instant> instants() {
        return Arbitraries.longs().between(0L, 4_000_000_000_000L).map(Instant::ofEpochMilli);
    }

    private Instant instantNow() {
        return Instant.parse("2024-01-01T00:00:00Z");
    }

    /** Timestamps encoded as Instant, epoch-millis, or ISO-8601 string. */
    private Arbitrary<Object> timestampValues() {
        return instants().flatMap(instant ->
                Arbitraries.of("instant", "millis", "iso").map(kind -> switch (kind) {
                    case "instant" -> instant;
                    case "millis" -> instant.toEpochMilli();
                    default -> instant.toString();
                }));
    }
}
