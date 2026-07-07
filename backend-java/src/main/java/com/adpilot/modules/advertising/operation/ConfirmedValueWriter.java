package com.adpilot.modules.advertising.operation;

import com.adpilot.modules.advertising.mapper.AdGroupMapper;
import com.adpilot.modules.advertising.mapper.CampaignMapper;
import com.adpilot.modules.advertising.mapper.GoalMapper;
import com.adpilot.modules.advertising.mapper.KeywordMapper;
import com.adpilot.modules.advertising.mapper.NegativeKeywordMapper;
import com.adpilot.modules.advertising.mapper.TargetMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Writes an Operation's after value onto the affected entity's <em>Amazon-confirmed</em> column —
 * the single mutation that is allowed to change a writable advertising field's confirmed value, and
 * only when the Operation has become {@code effective} (Req 3.4, 6.4, 7.4, 12.1).
 *
 * <p>This is the counterpart to {@link EntityVersionGuard}: where that guard claims a versioned
 * entity at the operator's loaded version without touching the confirmed value during the
 * side-effect-free first transaction (Req 6.1), this writer is invoked later, from
 * {@code OperationService.transition}, exactly when an Operation reaches {@code effective}. It maps
 * an Operation's {@code field} machine value to the entity's column and runs</p>
 *
 * <pre>{@code UPDATE <table> SET <column> = ?, version = version + 1 WHERE id = ?}</pre>
 *
 * <p>so the confirmed value is updated to the Operation's after value (Req 7.4) and the optimistic
 * lock advances. It indexes the versioned advertising entity mappers — Campaign, Goal, Ad_Group,
 * Keyword, Target, and Negative_Keyword — by their canonical {@code entityType} machine value.</p>
 *
 * <p>The {@code field} machine value is mapped to a physical column by converting camelCase to
 * snake_case, with a small override registry for the cases where the two diverge (for example a
 * Campaign daily-budget field whose column is simply {@code budget}). A multi-field Operation (no
 * {@code field}) or a non-versioned {@code entityType} is a no-op: there is no single confirmed
 * column to set, and the caller is responsible for those cases.</p>
 *
 * <p>Validates: Requirements 3.4, 5.8, 6.4, 7.4, 12.1.</p>
 */
@Component
public class ConfirmedValueWriter {

    private final OperationJsonCodec jsonCodec;
    private final Map<String, BaseMapper<?>> mappersByEntityType = new HashMap<>();

    /**
     * Column overrides for {@code (entityType -> (fieldMachineValue -> column))} pairs where the
     * snake_case convention does not match the physical column. Everything not listed here falls back
     * to the camelCase → snake_case conversion.
     */
    private final Map<String, Map<String, String>> columnOverrides = new HashMap<>();

    public ConfirmedValueWriter(OperationJsonCodec jsonCodec,
                                CampaignMapper campaignMapper,
                                GoalMapper goalMapper,
                                AdGroupMapper adGroupMapper,
                                KeywordMapper keywordMapper,
                                TargetMapper targetMapper,
                                NegativeKeywordMapper negativeKeywordMapper) {
        this.jsonCodec = jsonCodec;
        mappersByEntityType.put(EntityVersionGuard.CAMPAIGN, campaignMapper);
        mappersByEntityType.put(EntityVersionGuard.GOAL, goalMapper);
        mappersByEntityType.put(EntityVersionGuard.AD_GROUP, adGroupMapper);
        mappersByEntityType.put(EntityVersionGuard.KEYWORD, keywordMapper);
        mappersByEntityType.put(EntityVersionGuard.TARGET, targetMapper);
        mappersByEntityType.put(EntityVersionGuard.NEGATIVE_KEYWORD, negativeKeywordMapper);

        // The Campaign daily-budget field maps to the physical `budget` column.
        Map<String, String> campaignOverrides = new HashMap<>();
        campaignOverrides.put("dailyBudget", "budget");
        campaignOverrides.put("daily_budget", "budget");
        columnOverrides.put(EntityVersionGuard.CAMPAIGN, campaignOverrides);
    }

    /**
     * @return {@code true} if {@code entityType} is one of the versioned advertising entities whose
     *         confirmed value this writer can update.
     */
    public boolean isWritable(String entityType) {
        return entityType != null && mappersByEntityType.containsKey(entityType);
    }

    /**
     * Update the affected entity's Amazon-confirmed value for {@code field} to the Operation's after
     * value. This MUST be called only when the Operation has become {@code effective} (Req 7.4); the
     * caller ({@code OperationService.transition}) enforces that precondition.
     *
     * @param entityType     the Operation's entity type (canonical machine value)
     * @param entityId       the target object's id; must not be {@code null}
     * @param field          the single writable field changed; a {@code null}/blank field (a
     *                       multi-field Operation) is a no-op
     * @param afterValueJson the Operation's after value as stored JSON; a {@code null}/blank value is
     *                       a no-op
     * @return {@code true} when a confirmed-value column was updated; {@code false} when the write was
     *         skipped (non-versioned entity, no single field, or no after value)
     */
    public boolean applyConfirmedValue(String entityType, UUID entityId, String field, String afterValueJson) {
        if (entityId == null) {
            throw new IllegalArgumentException("entityId must not be null");
        }
        BaseMapper<?> mapper = mappersByEntityType.get(entityType);
        if (mapper == null) {
            // Non-versioned entity (or an entity type with no single confirmed column): nothing to do.
            return false;
        }
        if (field == null || field.isBlank()) {
            // Multi-field Operation: there is no single confirmed column to set here.
            return false;
        }
        if (afterValueJson == null || afterValueJson.isBlank()) {
            // No requested value to confirm.
            return false;
        }
        String column = resolveColumn(entityType, field);
        Object value = jsonCodec.fromJson(afterValueJson, Object.class);
        updateConfirmedColumn(mapper, column, value, entityId);
        return true;
    }

    /**
     * Read the affected entity's CURRENT Amazon-confirmed value for {@code field}. Used by the
     * effective-with-external-version-change reconciliation guard (Req 5.8) to capture the conflicting
     * local value when another writer changed the object during external execution.
     *
     * @param entityType the Operation's entity type (canonical machine value)
     * @param entityId   the target object's id
     * @param field      the single writable field; a {@code null}/blank field is treated as "no
     *                   single column to read"
     * @return the current confirmed-column value, or {@code null} when it cannot be read (non-versioned
     *         entity, multi-field Operation, or the row/column is absent)
     */
    public Object readConfirmedValue(String entityType, UUID entityId, String field) {
        if (entityId == null) {
            return null;
        }
        BaseMapper<?> mapper = mappersByEntityType.get(entityType);
        if (mapper == null) {
            return null;
        }
        if (field == null || field.isBlank()) {
            return null;
        }
        return readConfirmedColumn(mapper, resolveColumn(entityType, field), entityId);
    }

    /**
     * Detect whether the affected entity's CURRENT confirmed value for {@code field} has diverged from
     * the value the Operation recorded as its {@code before_value} at creation — i.e. another writer
     * changed the same object's confirmed value while this Operation was in flight (Req 5.8).
     *
     * <p>Returns {@code true} ONLY when a divergence can be positively determined: the entity is one
     * of the versioned advertising entities, the Operation targets a single field, a before value was
     * recorded, the current value can be read, and the current value does not equal the recorded
     * before value. In every other case it returns {@code false} (no detectable concurrent change), so
     * the caller proceeds with the normal effective application rather than false-flagging a conflict.</p>
     *
     * @param entityType            the Operation's entity type (canonical machine value)
     * @param entityId              the target object's id
     * @param field                 the single writable field changed
     * @param expectedBeforeValueJson the Operation's recorded {@code before_value} as stored JSON
     * @return {@code true} iff the current confirmed value differs from the recorded before value
     */
    public boolean confirmedValueChangedSince(String entityType, UUID entityId, String field,
                                              String expectedBeforeValueJson) {
        if (expectedBeforeValueJson == null || expectedBeforeValueJson.isBlank()) {
            return false;
        }
        Object current = readConfirmedValue(entityType, entityId, field);
        if (current == null) {
            // The current value could not be read (non-versioned/multi-field/absent): no detectable
            // concurrent change, so do not false-flag a conflict.
            return false;
        }
        Object expectedBefore = jsonCodec.fromJson(expectedBeforeValueJson, Object.class);
        return !valuesEqual(current, expectedBefore);
    }

    private <T> Object readConfirmedColumn(BaseMapper<T> mapper, String column, UUID entityId) {
        QueryWrapper<T> wrapper = new QueryWrapper<>();
        wrapper.select(column);
        // Bind id as its char(36) string form regardless of UUID type-handler configuration.
        wrapper.eq("id", entityId.toString());
        List<Map<String, Object>> rows = mapper.selectMaps(wrapper);
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        Map<String, Object> row = rows.get(0);
        if (row == null || row.isEmpty()) {
            return null;
        }
        if (row.containsKey(column)) {
            return row.get(column);
        }
        // Column-name casing may differ from the alias the driver returns; fall back to a
        // case-insensitive match, then to the sole value when only one column was projected.
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(column)) {
                return entry.getValue();
            }
        }
        if (row.size() == 1) {
            return row.values().iterator().next();
        }
        return null;
    }

    /**
     * Numeric-aware equality used by the Req 5.8 conflict detection: two numbers (or numeric strings)
     * are equal when their {@link BigDecimal} values compare equal (so {@code 1.0} equals {@code 1}),
     * otherwise values are compared by their string form.
     */
    static boolean valuesEqual(Object a, Object b) {
        if (a == null || b == null) {
            return a == b;
        }
        BigDecimal da = tryDecimal(a);
        BigDecimal db = tryDecimal(b);
        if (da != null && db != null) {
            return da.compareTo(db) == 0;
        }
        return String.valueOf(a).equals(String.valueOf(b));
    }

    private static BigDecimal tryDecimal(Object value) {
        if (value instanceof BigDecimal) {
            return (BigDecimal) value;
        }
        if (value instanceof Number) {
            return new BigDecimal(value.toString());
        }
        if (value instanceof String) {
            try {
                return new BigDecimal(((String) value).trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private String resolveColumn(String entityType, String field) {
        Map<String, String> overrides = columnOverrides.get(entityType);
        if (overrides != null && overrides.containsKey(field)) {
            return overrides.get(field);
        }
        return camelToSnake(field);
    }

    private <T> void updateConfirmedColumn(BaseMapper<T> mapper, String column, Object value, UUID entityId) {
        UpdateWrapper<T> wrapper = new UpdateWrapper<>();
        wrapper.set(column, value);
        // Advance the optimistic-lock version alongside the confirmed-value change.
        wrapper.setSql("version = version + 1");
        // Bind id as its char(36) string form regardless of UUID type-handler configuration.
        wrapper.eq("id", entityId.toString());
        mapper.update(null, wrapper);
    }

    /**
     * Convert a camelCase field machine value to its snake_case physical column
     * (for example {@code matchType} → {@code match_type}, {@code status} → {@code status}).
     */
    static String camelToSnake(String field) {
        StringBuilder sb = new StringBuilder(field.length() + 4);
        for (int i = 0; i < field.length(); i++) {
            char c = field.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) {
                    sb.append('_');
                }
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
