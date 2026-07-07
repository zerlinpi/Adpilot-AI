package com.adpilot.modules.audit.support;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Total mapping from raw audit action / entity-type codes to human-readable
 * Chinese labels (Requirement 11.1, 11.2).
 *
 * <p>The mapping is <strong>total</strong>: for <em>any</em> input string —
 * including {@code null}, blank, recognized, and unrecognized codes — the
 * {@link #actionLabel(String)} and {@link #entityTypeLabel(String)} methods
 * always return a non-empty label. Recognized codes are never rendered raw;
 * they always resolve to a distinct human-readable label. Unrecognized codes
 * are humanized (e.g. {@code "SOME_CODE" -> "Some Code"}) so the audit log
 * never surfaces an opaque machine code or an empty cell.
 *
 * <p>This class is the single source of truth targeted by the audit
 * label-mapping totality property test (Property 11, task 6.2).
 */
public final class AuditLabelTranslator {

    /** Fallback label for a null/blank action code. */
    public static final String UNKNOWN_ACTION_LABEL = "未知操作";
    /** Fallback label for a null/blank entity-type code. */
    public static final String UNKNOWN_ENTITY_TYPE_LABEL = "未知类型";

    /** Recognized action codes keyed by their upper-cased form → Chinese label. */
    private static final Map<String, String> ACTION_LABELS;
    /** Recognized entity-type codes keyed by their upper-cased form → Chinese label. */
    private static final Map<String, String> ENTITY_TYPE_LABELS;

    static {
        Map<String, String> actions = new LinkedHashMap<>();
        // Requirement 11.4 canonical filter actions
        actions.put("LOGIN", "登录");
        actions.put("LOGOUT", "登出");
        actions.put("CREATE", "创建");
        actions.put("UPDATE", "更新");
        actions.put("DELETE", "删除");
        actions.put("GENERATE", "生成");
        actions.put("APPLY", "应用");
        actions.put("ROLLBACK", "回滚");
        actions.put("APPROVE", "审批");
        actions.put("IGNORE", "忽略");
        actions.put("DISMISS", "忽略");
        // Additional codes emitted across the backend
        actions.put("REJECT", "拒绝");
        actions.put("EXPIRE", "过期");
        actions.put("WATCH", "关注");
        actions.put("EXECUTE_ROLLBACK", "执行回滚");
        actions.put("RISK_EVALUATE", "风险评估");
        actions.put("AUTHZ_PERMIT", "授权通过");
        actions.put("AUTHZ_DENY", "授权拒绝");
        actions.put("WRITEBACK_APPLIED", "写回应用");
        actions.put("WRITEBACK_REJECTED", "写回拒绝");
        actions.put("AUTOMATION_APPLIED", "自动化应用");
        actions.put("AUTOMATION_REJECTED", "自动化拒绝");
        ACTION_LABELS = Collections.unmodifiableMap(actions);

        Map<String, String> entities = new LinkedHashMap<>();
        // Requirement 11.5 canonical filter entity types
        entities.put("USER", "用户");
        entities.put("STORE", "店铺");
        entities.put("PRODUCT", "商品");
        entities.put("CAMPAIGN", "广告活动");
        entities.put("KEYWORD", "关键词");
        entities.put("REPORT", "报告");
        entities.put("RECOMMENDATION", "建议");
        entities.put("TASK", "任务");
        entities.put("APPROVAL", "审批");
        // Additional entity-type codes emitted across the backend
        entities.put("AUTHORIZATION", "授权");
        entities.put("AUTOMATION_RULE", "自动化规则");
        entities.put("AUTOMATION_POLICY", "自动化策略");
        entities.put("APPROVAL_REQUEST", "审批请求");
        ENTITY_TYPE_LABELS = Collections.unmodifiableMap(entities);
    }

    private AuditLabelTranslator() {
    }

    /**
     * Translate a raw audit action code into a human-readable label.
     *
     * @param code the raw action code (may be {@code null}, blank, or unrecognized)
     * @return a non-empty human-readable label; never the raw code for a recognized action
     */
    public static String actionLabel(String code) {
        return translate(code, ACTION_LABELS, UNKNOWN_ACTION_LABEL);
    }

    /**
     * Translate a raw audit entity-type code into a human-readable label.
     *
     * @param code the raw entity-type code (may be {@code null}, blank, or unrecognized)
     * @return a non-empty human-readable label; never the raw code for a recognized entity type
     */
    public static String entityTypeLabel(String code) {
        return translate(code, ENTITY_TYPE_LABELS, UNKNOWN_ENTITY_TYPE_LABEL);
    }

    /** Upper-cased recognized action codes (exposed for property testing). */
    public static Set<String> recognizedActionCodes() {
        return ACTION_LABELS.keySet();
    }

    /** Upper-cased recognized entity-type codes (exposed for property testing). */
    public static Set<String> recognizedEntityTypeCodes() {
        return ENTITY_TYPE_LABELS.keySet();
    }

    private static String translate(String code, Map<String, String> table, String fallback) {
        if (code == null) {
            return fallback;
        }
        String trimmed = code.trim();
        if (trimmed.isEmpty()) {
            return fallback;
        }
        String label = table.get(trimmed.toUpperCase(Locale.ROOT));
        if (label != null && !label.isEmpty()) {
            return label;
        }
        return humanize(trimmed);
    }

    /**
     * Convert an unrecognized code into a readable form by splitting on
     * separators and title-casing each segment. Guaranteed to return a
     * non-empty string for any non-blank input.
     */
    private static String humanize(String code) {
        String[] parts = code.replace('-', '_').replace('.', '_').split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                sb.append(part.substring(1).toLowerCase(Locale.ROOT));
            }
        }
        // If the code contained only separators/whitespace, fall back to the
        // trimmed code itself so the result is still non-empty.
        return sb.length() > 0 ? sb.toString() : code;
    }
}
