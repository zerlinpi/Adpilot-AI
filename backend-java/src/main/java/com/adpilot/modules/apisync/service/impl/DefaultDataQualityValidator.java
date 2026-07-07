package com.adpilot.modules.apisync.service.impl;

import com.adpilot.modules.apisync.model.FieldError;
import com.adpilot.modules.apisync.model.MappedRecord;
import com.adpilot.modules.apisync.model.ValidationResult;
import com.adpilot.modules.apisync.service.DataQualityValidator;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import static com.adpilot.modules.apisync.service.impl.DefaultRecordMapper.ENTITY_AD_REPORT;
import static com.adpilot.modules.apisync.service.impl.DefaultRecordMapper.ENTITY_INVENTORY;
import static com.adpilot.modules.apisync.service.impl.DefaultRecordMapper.ENTITY_ORDER;
import static com.adpilot.modules.apisync.service.impl.DefaultRecordMapper.ENTITY_PRODUCT;
import static com.adpilot.modules.apisync.service.impl.DefaultRecordMapper.F_CURRENCY;
import static com.adpilot.modules.apisync.service.impl.DefaultRecordMapper.F_DATE;
import static com.adpilot.modules.apisync.service.impl.DefaultRecordMapper.F_EXTERNAL_CAMPAIGN_ID;
import static com.adpilot.modules.apisync.service.impl.DefaultRecordMapper.F_EXTERNAL_ORDER_ID;
import static com.adpilot.modules.apisync.service.impl.DefaultRecordMapper.F_EXTERNAL_PRODUCT_ID;
import static com.adpilot.modules.apisync.service.impl.DefaultRecordMapper.F_ORDER_DATE;
import static com.adpilot.modules.apisync.service.impl.DefaultRecordMapper.F_ORDER_STATUS;
import static com.adpilot.modules.apisync.service.impl.DefaultRecordMapper.F_PRICE;
import static com.adpilot.modules.apisync.service.impl.DefaultRecordMapper.F_QUANTITY;
import static com.adpilot.modules.apisync.service.impl.DefaultRecordMapper.F_SALES;
import static com.adpilot.modules.apisync.service.impl.DefaultRecordMapper.F_SKU;
import static com.adpilot.modules.apisync.service.impl.DefaultRecordMapper.F_SPEND;
import static com.adpilot.modules.apisync.service.impl.DefaultRecordMapper.F_TITLE;
import static com.adpilot.modules.apisync.service.impl.DefaultRecordMapper.F_TOTAL_AMOUNT;

/**
 * Default {@link DataQualityValidator} carrying per-entity required-field rules
 * and type/format rules for {@code order} and {@code product} records mapped by
 * {@link DefaultRecordMapper} (Req 1.4.1).
 *
 * <p>Each rule failure produces exactly one {@link FieldError}; the collected
 * errors are returned as an invalid {@link ValidationResult} so the runner can
 * exclude the record and persist the violations (Req 1.4.2, 1.4.3).</p>
 */
@Component
public class DefaultDataQualityValidator implements DataQualityValidator {

    /** ISO-4217-style three-letter alphabetic currency code. */
    private static final Pattern CURRENCY_CODE = Pattern.compile("^[A-Za-z]{3}$");

    /** Rule set keyed by internal entity type. */
    private final Map<String, List<FieldRule>> rulesByEntity = Map.of(
            ENTITY_ORDER, orderRules(),
            ENTITY_PRODUCT, productRules(),
            ENTITY_INVENTORY, inventoryRules(),
            ENTITY_AD_REPORT, adReportRules());

    @Override
    public ValidationResult validate(String entityType, MappedRecord record) {
        if (record == null) {
            return ValidationResult.invalid(List.of(
                    FieldError.of(null, "required", "Record is required")));
        }
        if (entityType == null) {
            return ValidationResult.invalid(List.of(
                    FieldError.of(null, "entity_type", "Entity type is required")));
        }

        List<FieldRule> rules = rulesByEntity.get(entityType);
        if (rules == null) {
            return ValidationResult.invalid(List.of(FieldError.of(null, "entity_type",
                    "Unsupported entity type '" + entityType + "'")));
        }

        Map<String, Object> fields = record.fields() == null ? Map.of() : record.fields();
        List<FieldError> errors = new ArrayList<>();
        for (FieldRule rule : rules) {
            rule.evaluate(fields).ifPresent(errors::add);
        }
        return ValidationResult.of(errors);
    }

    // --- per-entity rule sets -------------------------------------------------

    private static List<FieldRule> orderRules() {
        return List.of(
                FieldRule.requiredString(F_EXTERNAL_ORDER_ID),
                FieldRule.requiredString(F_ORDER_STATUS),
                FieldRule.requiredNonNegativeDecimal(F_TOTAL_AMOUNT),
                FieldRule.requiredInstant(F_ORDER_DATE),
                FieldRule.optionalCurrency(F_CURRENCY));
    }

    private static List<FieldRule> productRules() {
        return List.of(
                FieldRule.requiredString(F_EXTERNAL_PRODUCT_ID),
                FieldRule.requiredString(F_SKU),
                FieldRule.requiredString(F_TITLE),
                FieldRule.requiredNonNegativeDecimal(F_PRICE),
                FieldRule.optionalCurrency(F_CURRENCY));
    }

    /**
     * Amazon SP-API inventory (Req 8.1.1): the external product identifier
     * anchors the idempotent upsert and the reported quantity, when present,
     * must not be negative.
     */
    private static List<FieldRule> inventoryRules() {
        return List.of(
                FieldRule.requiredString(F_EXTERNAL_PRODUCT_ID),
                FieldRule.optionalNonNegativeDecimal(F_QUANTITY));
    }

    /**
     * Amazon Ads report rows (Req 8.1.3): the external campaign identifier and
     * report date anchor the idempotent upsert into {@code performance_daily};
     * spend and sales, when present, must not be negative.
     */
    private static List<FieldRule> adReportRules() {
        return List.of(
                FieldRule.requiredString(F_EXTERNAL_CAMPAIGN_ID),
                FieldRule.requiredInstant(F_DATE),
                FieldRule.optionalNonNegativeDecimal(F_SPEND),
                FieldRule.optionalNonNegativeDecimal(F_SALES));
    }

    // --- rule model -----------------------------------------------------------

    /**
     * A single declarative rule for one field. Evaluates presence first
     * (required) then type, then an optional format predicate, returning at most
     * one {@link FieldError} (the first failing aspect) so a field contributes a
     * single error.
     */
    private record FieldRule(String field,
                             boolean required,
                             Class<?> expectedType,
                             String expectedTypeName,
                             Predicate<Object> format,
                             String formatCode,
                             String formatMessage) {

        java.util.Optional<FieldError> evaluate(Map<String, Object> fields) {
            Object value = fields.get(field);

            // Treat null and blank strings as absent.
            boolean absent = value == null
                    || (value instanceof String s && s.isBlank());
            if (absent) {
                return required
                        ? java.util.Optional.of(FieldError.required(field))
                        : java.util.Optional.empty();
            }

            if (expectedType != null && !expectedType.isInstance(value)) {
                return java.util.Optional.of(FieldError.type(field, expectedTypeName));
            }

            if (format != null && !format.test(value)) {
                return java.util.Optional.of(
                        FieldError.of(field, formatCode, formatMessage));
            }

            return java.util.Optional.empty();
        }

        static FieldRule requiredString(String field) {
            return new FieldRule(field, true, String.class, "string",
                    null, null, null);
        }

        static FieldRule requiredInstant(String field) {
            return new FieldRule(field, true, Instant.class, "timestamp",
                    null, null, null);
        }

        static FieldRule requiredNonNegativeDecimal(String field) {
            return new FieldRule(field, true, BigDecimal.class, "decimal",
                    value -> ((BigDecimal) value).signum() >= 0,
                    "format",
                    "Field '" + field + "' must not be negative");
        }

        static FieldRule optionalNonNegativeDecimal(String field) {
            return new FieldRule(field, false, BigDecimal.class, "decimal",
                    value -> ((BigDecimal) value).signum() >= 0,
                    "format",
                    "Field '" + field + "' must not be negative");
        }

        static FieldRule optionalCurrency(String field) {
            return new FieldRule(field, false, String.class, "string",
                    value -> CURRENCY_CODE.matcher((String) value).matches(),
                    "format",
                    "Field '" + field + "' must be a 3-letter currency code");
        }
    }
}
