package com.adpilot.modules.apisync.service.impl;

import com.adpilot.modules.apisync.model.FieldError;
import com.adpilot.modules.apisync.model.MappedRecord;
import com.adpilot.modules.apisync.model.ValidationResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultDataQualityValidatorTest {

    private final DefaultDataQualityValidator validator = new DefaultDataQualityValidator();

    private MappedRecord order(Map<String, Object> fields) {
        return new MappedRecord("ext-1", "order", UUID.randomUUID(), "processing",
                fields, Instant.parse("2024-01-01T00:00:00Z"));
    }

    private MappedRecord product(Map<String, Object> fields) {
        return new MappedRecord("ext-1", "product", UUID.randomUUID(), "active",
                fields, Instant.parse("2024-01-01T00:00:00Z"));
    }

    private Map<String, Object> validOrderFields() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(DefaultRecordMapper.F_EXTERNAL_ORDER_ID, "ext-order-1");
        fields.put(DefaultRecordMapper.F_ORDER_STATUS, "processing");
        fields.put(DefaultRecordMapper.F_TOTAL_AMOUNT, new BigDecimal("42.50"));
        fields.put(DefaultRecordMapper.F_ORDER_DATE, Instant.parse("2024-01-01T00:00:00Z"));
        fields.put(DefaultRecordMapper.F_CURRENCY, "USD");
        return fields;
    }

    private Map<String, Object> validProductFields() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(DefaultRecordMapper.F_EXTERNAL_PRODUCT_ID, "ext-prod-1");
        fields.put(DefaultRecordMapper.F_SKU, "SKU-1");
        fields.put(DefaultRecordMapper.F_TITLE, "Wireless Mouse");
        fields.put(DefaultRecordMapper.F_PRICE, new BigDecimal("19.99"));
        fields.put(DefaultRecordMapper.F_CURRENCY, "EUR");
        return fields;
    }

    @Test
    void validOrderPassesWithNoErrors() {
        ValidationResult result = validator.validate("order", order(validOrderFields()));

        assertThat(result.valid()).isTrue();
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void validProductPassesWithNoErrors() {
        ValidationResult result = validator.validate("product", product(validProductFields()));

        assertThat(result.valid()).isTrue();
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void validInventoryPassesWithNoErrors() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(DefaultRecordMapper.F_EXTERNAL_PRODUCT_ID, "SKU-AMZ-1");
        fields.put(DefaultRecordMapper.F_SKU, "SKU-AMZ-1");
        fields.put(DefaultRecordMapper.F_QUANTITY, new BigDecimal("42"));
        MappedRecord record = new MappedRecord("SKU-AMZ-1", "inventory", UUID.randomUUID(),
                "InStock", fields, Instant.parse("2024-01-01T00:00:00Z"));

        ValidationResult result = validator.validate("inventory", record);

        assertThat(result.valid()).isTrue();
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void inventoryWithNegativeQuantityIsReported() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(DefaultRecordMapper.F_EXTERNAL_PRODUCT_ID, "SKU-AMZ-1");
        fields.put(DefaultRecordMapper.F_QUANTITY, new BigDecimal("-1"));
        MappedRecord record = new MappedRecord("SKU-AMZ-1", "inventory", UUID.randomUUID(),
                "InStock", fields, Instant.parse("2024-01-01T00:00:00Z"));

        ValidationResult result = validator.validate("inventory", record);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors())
                .extracting(FieldError::field)
                .contains(DefaultRecordMapper.F_QUANTITY);
    }

    @Test
    void validAdReportPassesWithNoErrors() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(DefaultRecordMapper.F_EXTERNAL_CAMPAIGN_ID, "AMZ-CAMP-1");
        fields.put(DefaultRecordMapper.F_DATE, Instant.parse("2024-03-01T00:00:00Z"));
        fields.put(DefaultRecordMapper.F_SPEND, new BigDecimal("12.50"));
        fields.put(DefaultRecordMapper.F_SALES, new BigDecimal("99.00"));
        MappedRecord record = new MappedRecord("AMZ-CAMP-1#2024-03-01", "ad_report",
                UUID.randomUUID(), null, fields, Instant.parse("2024-03-01T00:00:00Z"));

        ValidationResult result = validator.validate("ad_report", record);

        assertThat(result.valid()).isTrue();
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void adReportMissingCampaignIdAndDateAreReported() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(DefaultRecordMapper.F_SPEND, new BigDecimal("1.00"));
        MappedRecord record = new MappedRecord("AMZ-CAMP-1#2024-03-01", "ad_report",
                UUID.randomUUID(), null, fields, Instant.parse("2024-03-01T00:00:00Z"));

        ValidationResult result = validator.validate("ad_report", record);

        assertThat(result.valid()).isFalse();
        assertThat(result.errors())
                .extracting(FieldError::field, FieldError::code)
                .contains(
                        org.assertj.core.groups.Tuple.tuple(DefaultRecordMapper.F_EXTERNAL_CAMPAIGN_ID, "required"),
                        org.assertj.core.groups.Tuple.tuple(DefaultRecordMapper.F_DATE, "required"));
    }

    @Test
    void missingRequiredOrderFieldsAreReported() {
        Map<String, Object> fields = validOrderFields();
        fields.remove(DefaultRecordMapper.F_ORDER_STATUS);
        fields.remove(DefaultRecordMapper.F_TOTAL_AMOUNT);

        ValidationResult result = validator.validate("order", order(fields));

        assertThat(result.valid()).isFalse();
        assertThat(result.errors())
                .extracting(FieldError::field, FieldError::code)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(DefaultRecordMapper.F_ORDER_STATUS, "required"),
                        org.assertj.core.groups.Tuple.tuple(DefaultRecordMapper.F_TOTAL_AMOUNT, "required"));
    }

    @Test
    void blankStringTreatedAsMissing() {
        Map<String, Object> fields = validProductFields();
        fields.put(DefaultRecordMapper.F_SKU, "   ");

        ValidationResult result = validator.validate("product", product(fields));

        assertThat(result.valid()).isFalse();
        assertThat(result.errors())
                .extracting(FieldError::field, FieldError::code)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(DefaultRecordMapper.F_SKU, "required"));
    }

    @Test
    void wrongTypeForDecimalFieldIsReportedAsTypeError() {
        Map<String, Object> fields = validProductFields();
        fields.put(DefaultRecordMapper.F_PRICE, "19.99"); // String, not BigDecimal

        ValidationResult result = validator.validate("product", product(fields));

        assertThat(result.valid()).isFalse();
        assertThat(result.errors())
                .extracting(FieldError::field, FieldError::code)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(DefaultRecordMapper.F_PRICE, "type"));
    }

    @Test
    void wrongTypeForOrderDateIsReportedAsTypeError() {
        Map<String, Object> fields = validOrderFields();
        fields.put(DefaultRecordMapper.F_ORDER_DATE, "2024-01-01"); // String, not Instant

        ValidationResult result = validator.validate("order", order(fields));

        assertThat(result.errors())
                .extracting(FieldError::field, FieldError::code)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(DefaultRecordMapper.F_ORDER_DATE, "type"));
    }

    @Test
    void negativeDecimalIsReportedAsFormatError() {
        Map<String, Object> fields = validOrderFields();
        fields.put(DefaultRecordMapper.F_TOTAL_AMOUNT, new BigDecimal("-5.00"));

        ValidationResult result = validator.validate("order", order(fields));

        assertThat(result.valid()).isFalse();
        assertThat(result.errors())
                .extracting(FieldError::field, FieldError::code)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(DefaultRecordMapper.F_TOTAL_AMOUNT, "format"));
    }

    @Test
    void invalidCurrencyFormatIsReported() {
        Map<String, Object> fields = validProductFields();
        fields.put(DefaultRecordMapper.F_CURRENCY, "DOLLARS");

        ValidationResult result = validator.validate("product", product(fields));

        assertThat(result.valid()).isFalse();
        assertThat(result.errors())
                .extracting(FieldError::field, FieldError::code)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(DefaultRecordMapper.F_CURRENCY, "format"));
    }

    @Test
    void absentOptionalCurrencyIsAllowed() {
        Map<String, Object> fields = validProductFields();
        fields.remove(DefaultRecordMapper.F_CURRENCY);

        ValidationResult result = validator.validate("product", product(fields));

        assertThat(result.valid()).isTrue();
    }

    @Test
    void eachFailingFieldContributesExactlyOneError() {
        Map<String, Object> fields = new HashMap<>(); // empty: all required missing
        ValidationResult result = validator.validate("order", order(fields));

        // 4 required fields for order; currency optional and absent -> no error.
        assertThat(result.errors()).hasSize(4);
        assertThat(result.errors()).extracting(FieldError::field)
                .containsExactlyInAnyOrder(
                        DefaultRecordMapper.F_EXTERNAL_ORDER_ID,
                        DefaultRecordMapper.F_ORDER_STATUS,
                        DefaultRecordMapper.F_TOTAL_AMOUNT,
                        DefaultRecordMapper.F_ORDER_DATE);
    }

    @Test
    void unsupportedEntityTypeIsInvalidRatherThanThrowing() {
        ValidationResult result = validator.validate("settlement", order(validOrderFields()));

        assertThat(result.valid()).isFalse();
        assertThat(result.errors())
                .extracting(FieldError::code)
                .containsExactly("entity_type");
    }

    @Test
    void nullEntityTypeIsInvalid() {
        ValidationResult result = validator.validate(null, order(validOrderFields()));

        assertThat(result.valid()).isFalse();
    }

    @Test
    void nullRecordIsInvalid() {
        ValidationResult result = validator.validate("order", null);

        assertThat(result.valid()).isFalse();
    }
}
