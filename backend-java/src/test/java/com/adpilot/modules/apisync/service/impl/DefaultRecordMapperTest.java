package com.adpilot.modules.apisync.service.impl;

import com.adpilot.modules.apisync.model.ExternalRecord;
import com.adpilot.modules.apisync.model.MappedRecord;
import com.adpilot.modules.apisync.model.SyncContext;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultRecordMapperTest {

    private final DefaultRecordMapper mapper = new DefaultRecordMapper();

    private SyncContext context(UUID storeId, String entityType) {
        return new SyncContext(UUID.randomUUID(), UUID.randomUUID(), storeId,
                "woocommerce", entityType, false);
    }

    @Test
    void mapsProductFieldsWithCorrectTypes() {
        UUID storeId = UUID.randomUUID();
        Map<String, Object> fields = new HashMap<>();
        fields.put("sku", "SKU-1");
        fields.put("title", "Wireless Mouse");
        fields.put("price", "19.99");
        fields.put("currency", "USD");

        ExternalRecord record = new ExternalRecord("ext-prod-1", "product",
                Instant.parse("2024-01-01T00:00:00Z"), "active", fields);

        MappedRecord mapped = mapper.map(context(storeId, "product"), record);

        assertThat(mapped.entityType()).isEqualTo("product");
        assertThat(mapped.externalId()).isEqualTo("ext-prod-1");
        assertThat(mapped.storeId()).isEqualTo(storeId);
        assertThat(mapped.fields().get(DefaultRecordMapper.F_EXTERNAL_PRODUCT_ID)).isEqualTo("ext-prod-1");
        assertThat(mapped.fields().get(DefaultRecordMapper.F_SKU)).isEqualTo("SKU-1");
        assertThat(mapped.fields().get(DefaultRecordMapper.F_TITLE)).isEqualTo("Wireless Mouse");
        assertThat(mapped.fields().get(DefaultRecordMapper.F_PRICE))
                .isInstanceOf(BigDecimal.class)
                .isEqualTo(new BigDecimal("19.99"));
        assertThat(mapped.fields().get(DefaultRecordMapper.F_CURRENCY)).isEqualTo("USD");
        assertThat(mapped.fields().get(DefaultRecordMapper.F_STATUS)).isEqualTo("active");
    }

    @Test
    void mapsOrderFieldsWithCorrectTypes() {
        UUID storeId = UUID.randomUUID();
        Instant orderDate = Instant.parse("2024-03-15T10:30:00Z");
        Map<String, Object> fields = new HashMap<>();
        fields.put("totalAmount", 42.5d);
        fields.put("currency", "EUR");
        fields.put("orderDate", orderDate);

        ExternalRecord record = new ExternalRecord("ext-order-9", "order",
                Instant.parse("2024-03-15T11:00:00Z"), "processing", fields);

        MappedRecord mapped = mapper.map(context(storeId, "order"), record);

        assertThat(mapped.entityType()).isEqualTo("order");
        assertThat(mapped.storeId()).isEqualTo(storeId);
        assertThat(mapped.fields().get(DefaultRecordMapper.F_EXTERNAL_ORDER_ID)).isEqualTo("ext-order-9");
        assertThat(mapped.fields().get(DefaultRecordMapper.F_ORDER_STATUS)).isEqualTo("processing");
        assertThat(mapped.fields().get(DefaultRecordMapper.F_TOTAL_AMOUNT))
                .isInstanceOf(BigDecimal.class)
                .isEqualTo(new BigDecimal("42.5"));
        assertThat(mapped.fields().get(DefaultRecordMapper.F_CURRENCY)).isEqualTo("EUR");
        assertThat(mapped.fields().get(DefaultRecordMapper.F_ORDER_DATE)).isEqualTo(orderDate);
    }

    @Test
    void associatesMappedRecordWithStoreFromContext() {
        UUID storeId = UUID.randomUUID();
        ExternalRecord record = new ExternalRecord("ext-1", "product",
                Instant.now(), "active", Map.of("sku", "S1"));

        MappedRecord mapped = mapper.map(context(storeId, "product"), record);

        assertThat(mapped.storeId()).isEqualTo(storeId);
    }

    @Test
    void orderDateFallsBackToChangedAtWhenAbsent() {
        Instant changedAt = Instant.parse("2024-05-01T08:00:00Z");
        ExternalRecord record = new ExternalRecord("ext-2", "order",
                changedAt, "completed", Map.of("totalAmount", "10.00"));

        MappedRecord mapped = mapper.map(context(UUID.randomUUID(), "order"), record);

        assertThat(mapped.fields().get(DefaultRecordMapper.F_ORDER_DATE)).isEqualTo(changedAt);
    }

    @Test
    void retainsRawDataForMappedRecord() {
        Map<String, Object> fields = Map.of("sku", "S2", "extra", "keep-me");
        ExternalRecord record = new ExternalRecord("ext-3", "product",
                Instant.now(), "active", fields);

        MappedRecord mapped = mapper.map(context(UUID.randomUUID(), "product"), record);

        @SuppressWarnings("unchecked")
        Map<String, Object> raw = (Map<String, Object>) mapped.fields().get(DefaultRecordMapper.F_RAW_DATA);
        assertThat(raw).containsEntry("extra", "keep-me").containsEntry("sku", "S2");
    }

    @Test
    void unparseableDecimalBecomesNullRatherThanThrowing() {
        ExternalRecord record = new ExternalRecord("ext-4", "product",
                Instant.now(), "active", Map.of("price", "not-a-number"));

        MappedRecord mapped = mapper.map(context(UUID.randomUUID(), "product"), record);

        assertThat(mapped.fields().get(DefaultRecordMapper.F_PRICE)).isNull();
    }

    @Test
    void parsesEpochMillisAndIsoStringsForOrderDate() {
        Instant epoch = Instant.parse("2024-02-02T02:02:02Z");
        ExternalRecord epochRecord = new ExternalRecord("o1", "order", Instant.now(), "x",
                Map.of("totalAmount", 1, "orderDate", epoch.toEpochMilli()));
        ExternalRecord isoRecord = new ExternalRecord("o2", "order", Instant.now(), "x",
                Map.of("totalAmount", 1, "orderDate", "2024-02-02T02:02:02Z"));

        assertThat(mapper.map(context(UUID.randomUUID(), "order"), epochRecord)
                .fields().get(DefaultRecordMapper.F_ORDER_DATE)).isEqualTo(epoch);
        assertThat(mapper.map(context(UUID.randomUUID(), "order"), isoRecord)
                .fields().get(DefaultRecordMapper.F_ORDER_DATE)).isEqualTo(epoch);
    }

    @Test
    void mapsInventoryFieldsWithCorrectTypes() {
        UUID storeId = UUID.randomUUID();
        Map<String, Object> fields = new HashMap<>();
        fields.put("sellerSku", "SKU-AMZ-1");
        fields.put("asin", "B000TEST");
        fields.put("totalQuantity", 42);

        ExternalRecord record = new ExternalRecord("SKU-AMZ-1", "inventory",
                Instant.parse("2024-04-01T00:00:00Z"), "InStock", fields);

        MappedRecord mapped = mapper.map(context(storeId, "inventory"), record);

        assertThat(mapped.entityType()).isEqualTo("inventory");
        assertThat(mapped.storeId()).isEqualTo(storeId);
        assertThat(mapped.fields().get(DefaultRecordMapper.F_EXTERNAL_PRODUCT_ID)).isEqualTo("SKU-AMZ-1");
        assertThat(mapped.fields().get(DefaultRecordMapper.F_SKU)).isEqualTo("SKU-AMZ-1");
        assertThat(mapped.fields().get(DefaultRecordMapper.F_QUANTITY))
                .isInstanceOf(BigDecimal.class)
                .isEqualTo(new BigDecimal("42"));
    }

    @Test
    void mapsAdReportMetricsWithCorrectTypes() {
        UUID storeId = UUID.randomUUID();
        Map<String, Object> fields = new HashMap<>();
        fields.put("external_campaign_id", "AMZ-CAMP-1");
        fields.put("entity_type", "campaign");
        fields.put("date", "2024-03-01");
        fields.put("impressions", 1000);
        fields.put("clicks", 25);
        fields.put("spend", "12.50");
        fields.put("sales", "99.00");

        ExternalRecord record = new ExternalRecord("AMZ-CAMP-1#2024-03-01", "ad_report",
                Instant.parse("2024-03-01T00:00:00Z"), null, fields);

        MappedRecord mapped = mapper.map(context(storeId, "ad_report"), record);

        assertThat(mapped.entityType()).isEqualTo("ad_report");
        assertThat(mapped.storeId()).isEqualTo(storeId);
        assertThat(mapped.fields().get(DefaultRecordMapper.F_EXTERNAL_CAMPAIGN_ID)).isEqualTo("AMZ-CAMP-1");
        assertThat(mapped.fields().get(DefaultRecordMapper.F_ENTITY_TYPE)).isEqualTo("campaign");
        assertThat(mapped.fields().get(DefaultRecordMapper.F_DATE))
                .isInstanceOf(Instant.class)
                .isEqualTo(Instant.parse("2024-03-01T00:00:00Z"));
        assertThat(mapped.fields().get(DefaultRecordMapper.F_SPEND))
                .isInstanceOf(BigDecimal.class)
                .isEqualTo(new BigDecimal("12.50"));
        assertThat(mapped.fields().get(DefaultRecordMapper.F_SALES))
                .isInstanceOf(BigDecimal.class)
                .isEqualTo(new BigDecimal("99.00"));
    }

    @Test
    void adReportDateFallsBackToChangedAtWhenAbsent() {
        Instant changedAt = Instant.parse("2024-06-01T00:00:00Z");
        ExternalRecord record = new ExternalRecord("AMZ-CAMP-2#2024-06-01", "ad_report",
                changedAt, null, Map.of("external_campaign_id", "AMZ-CAMP-2"));

        MappedRecord mapped = mapper.map(context(UUID.randomUUID(), "ad_report"), record);

        assertThat(mapped.fields().get(DefaultRecordMapper.F_DATE)).isEqualTo(changedAt);
    }

    @Test
    void rejectsUnsupportedEntityType() {
        ExternalRecord record = new ExternalRecord("ext-5", "settlement",
                Instant.now(), "active", Map.of());

        assertThatThrownBy(() -> mapper.map(context(UUID.randomUUID(), "settlement"), record))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("settlement");
    }
}
