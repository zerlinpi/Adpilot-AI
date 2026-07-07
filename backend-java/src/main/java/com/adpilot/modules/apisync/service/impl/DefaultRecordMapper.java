package com.adpilot.modules.apisync.service.impl;

import com.adpilot.modules.apisync.model.ExternalRecord;
import com.adpilot.modules.apisync.model.MappedRecord;
import com.adpilot.modules.apisync.model.SyncContext;
import com.adpilot.modules.apisync.service.RecordMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Default {@link RecordMapper} for WooCommerce/Shopify-style order and product
 * records. Reads normalized field values from the {@link ExternalRecord} and
 * emits internal field values with the correct Java types for the
 * {@code channel_orders} / {@code channel_products} tables, associating each
 * mapped record with the Store that owns the originating connection
 * (Req 1.1.3, 1.1.4).
 */
@Component
public class DefaultRecordMapper implements RecordMapper {

    // Supported internal entity types.
    public static final String ENTITY_ORDER = "order";
    public static final String ENTITY_PRODUCT = "product";
    public static final String ENTITY_INVENTORY = "inventory";
    public static final String ENTITY_AD_REPORT = "ad_report";

    // Internal field names for channel_orders.
    public static final String F_EXTERNAL_ORDER_ID = "external_order_id";
    public static final String F_ORDER_STATUS = "order_status";
    public static final String F_TOTAL_AMOUNT = "total_amount";
    public static final String F_ORDER_DATE = "order_date";

    // Internal field names for channel_products.
    public static final String F_EXTERNAL_PRODUCT_ID = "external_product_id";
    public static final String F_SKU = "sku";
    public static final String F_TITLE = "title";
    public static final String F_PRICE = "price";

    // Shared internal field names.
    public static final String F_CURRENCY = "currency";
    public static final String F_STATUS = "status";
    public static final String F_RAW_DATA = "raw_data";

    // Internal field names for channel_inventory_sync (Amazon SP-API inventory).
    public static final String F_QUANTITY = "quantity";

    // Internal field names for performance_daily (Amazon Ads reports, Req 8.1.3).
    public static final String F_DATE = "date";
    public static final String F_ENTITY_TYPE = "entity_type";
    public static final String F_EXTERNAL_CAMPAIGN_ID = "external_campaign_id";
    public static final String F_IMPRESSIONS = "impressions";
    public static final String F_CLICKS = "clicks";
    public static final String F_SPEND = "spend";
    public static final String F_SALES = "sales";
    public static final String F_ORDERS = "orders";
    public static final String F_ACOS = "acos";
    public static final String F_ROAS = "roas";
    public static final String F_CTR = "ctr";
    public static final String F_CVR = "cvr";
    public static final String F_AVG_CPC = "avg_cpc";

    @Override
    public MappedRecord map(SyncContext ctx, ExternalRecord record) {
        if (ctx == null) {
            throw new IllegalArgumentException("SyncContext is required for store association");
        }
        if (record == null) {
            throw new IllegalArgumentException("ExternalRecord is required");
        }
        String entityType = record.entityType();
        if (entityType == null) {
            throw new IllegalArgumentException("ExternalRecord.entityType is required");
        }

        UUID storeId = ctx.storeId();
        Map<String, Object> source = record.fields() == null ? Map.of() : record.fields();

        Map<String, Object> fields = switch (entityType) {
            case ENTITY_ORDER -> mapOrder(record, source);
            case ENTITY_PRODUCT -> mapProduct(record, source);
            case ENTITY_INVENTORY -> mapInventory(record, source);
            case ENTITY_AD_REPORT -> mapAdReport(record, source);
            default -> throw new IllegalArgumentException(
                    "Unsupported entity type for mapping: '" + entityType + "'");
        };

        return new MappedRecord(
                record.externalId(),
                entityType,
                storeId,
                record.status(),
                fields,
                record.changedAt());
    }

    private Map<String, Object> mapOrder(ExternalRecord record, Map<String, Object> source) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(F_EXTERNAL_ORDER_ID, record.externalId());
        fields.put(F_ORDER_STATUS, firstString(source, record.status(), "orderStatus", "order_status", "status"));
        fields.put(F_TOTAL_AMOUNT, firstDecimal(source, "totalAmount", "total_amount", "total", "amount"));
        fields.put(F_CURRENCY, firstString(source, null, "currency", "currencyCode", "currency_code"));
        // Prefer an explicit order date; fall back to the platform-reported change time.
        Instant orderDate = firstInstant(source, "orderDate", "order_date", "createdAt", "created_at", "dateCreated");
        fields.put(F_ORDER_DATE, orderDate != null ? orderDate : record.changedAt());
        fields.put(F_RAW_DATA, new LinkedHashMap<>(source));
        return fields;
    }

    private Map<String, Object> mapProduct(ExternalRecord record, Map<String, Object> source) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(F_EXTERNAL_PRODUCT_ID, record.externalId());
        fields.put(F_SKU, firstString(source, null, "sku", "SKU", "skuCode"));
        fields.put(F_TITLE, firstString(source, null, "title", "name", "productName", "product_name"));
        fields.put(F_PRICE, firstDecimal(source, "price", "regularPrice", "regular_price", "amount"));
        fields.put(F_CURRENCY, firstString(source, null, "currency", "currencyCode", "currency_code"));
        fields.put(F_STATUS, firstString(source, record.status(), "status"));
        fields.put(F_RAW_DATA, new LinkedHashMap<>(source));
        return fields;
    }

    /**
     * Map an Amazon SP-API FBA inventory summary to {@code channel_inventory_sync}
     * fields. The external identifier (seller SKU / ASIN / FNSKU) anchors the
     * idempotent upsert (Req 8.1.4).
     */
    private Map<String, Object> mapInventory(ExternalRecord record, Map<String, Object> source) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(F_EXTERNAL_PRODUCT_ID, record.externalId());
        fields.put(F_SKU, firstString(source, null, "sellerSku", "sku", "SKU", "asin", "fnSku"));
        fields.put(F_QUANTITY, firstDecimal(source,
                "totalQuantity", "quantity", "fulfillableQuantity", "availableQuantity"));
        fields.put(F_RAW_DATA, new LinkedHashMap<>(source));
        return fields;
    }

    /**
     * Map an Amazon Ads report row to {@code performance_daily} fields (Req 8.1.3).
     * The connector has already folded Amazon's per-metric key variants onto the
     * internal field names; this stage coerces them to the target Java types and
     * carries the external campaign identifier and report date used to anchor the
     * idempotent upsert (Req 8.1.4).
     */
    private Map<String, Object> mapAdReport(ExternalRecord record, Map<String, Object> source) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(F_EXTERNAL_CAMPAIGN_ID,
                firstString(source, record.externalId(), F_EXTERNAL_CAMPAIGN_ID, "campaignId", "campaign_id"));
        fields.put(F_ENTITY_TYPE, firstString(source, "campaign", F_ENTITY_TYPE));
        // Prefer an explicit report date; fall back to the platform-reported change time.
        Instant date = firstInstant(source, F_DATE, "reportDate", "day");
        fields.put(F_DATE, date != null ? date : record.changedAt());
        fields.put(F_IMPRESSIONS, firstDecimal(source, F_IMPRESSIONS));
        fields.put(F_CLICKS, firstDecimal(source, F_CLICKS));
        fields.put(F_SPEND, firstDecimal(source, F_SPEND, "cost"));
        fields.put(F_SALES, firstDecimal(source, F_SALES));
        fields.put(F_ORDERS, firstDecimal(source, F_ORDERS, "purchases"));
        fields.put(F_ACOS, firstDecimal(source, F_ACOS));
        fields.put(F_ROAS, firstDecimal(source, F_ROAS));
        fields.put(F_CTR, firstDecimal(source, F_CTR));
        fields.put(F_CVR, firstDecimal(source, F_CVR));
        fields.put(F_AVG_CPC, firstDecimal(source, F_AVG_CPC));
        fields.put(F_RAW_DATA, new LinkedHashMap<>(source));
        return fields;
    }

    // --- type-coercion helpers ------------------------------------------------

    /**
     * Return the first non-null candidate field as a trimmed String, falling
     * back to {@code defaultValue} when none of the candidate keys are present.
     */
    private String firstString(Map<String, Object> source, String defaultValue, String... keys) {
        Object value = firstPresent(source, keys);
        if (value == null) {
            return defaultValue;
        }
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? defaultValue : s;
    }

    /**
     * Return the first non-null candidate field coerced to {@link BigDecimal}.
     * Numbers are converted exactly; numeric strings are parsed. Non-numeric
     * values yield {@code null} so the data-quality validator can flag them.
     */
    private BigDecimal firstDecimal(Map<String, Object> source, String... keys) {
        Object value = firstPresent(source, keys);
        return toDecimal(value);
    }

    /**
     * Return the first non-null candidate field coerced to {@link Instant}.
     * Supports {@link Instant}, {@link OffsetDateTime}, {@link LocalDateTime},
     * {@link LocalDate}, epoch-millisecond numbers, and ISO-8601 strings.
     */
    private Instant firstInstant(Map<String, Object> source, String... keys) {
        Object value = firstPresent(source, keys);
        return toInstant(value);
    }

    private Object firstPresent(Map<String, Object> source, String... keys) {
        for (String key : keys) {
            Object value = source.get(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    static BigDecimal toDecimal(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal bd) {
            return bd;
        }
        if (value instanceof Number n) {
            // Use the canonical string form to avoid binary floating-point drift.
            return new BigDecimal(n.toString());
        }
        String s = String.valueOf(value).trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    static Instant toInstant(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Instant instant) {
            return instant;
        }
        if (value instanceof OffsetDateTime odt) {
            return odt.toInstant();
        }
        if (value instanceof LocalDateTime ldt) {
            return ldt.toInstant(ZoneOffset.UTC);
        }
        if (value instanceof LocalDate ld) {
            return ld.atStartOfDay(ZoneOffset.UTC).toInstant();
        }
        if (value instanceof Number n) {
            return Instant.ofEpochMilli(n.longValue());
        }
        String s = String.valueOf(value).trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(s).toInstant();
        } catch (DateTimeParseException ignored) {
            // fall through to other formats
        }
        try {
            return Instant.parse(s);
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        try {
            return LocalDateTime.parse(s).toInstant(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        try {
            return LocalDate.parse(s).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }
}
