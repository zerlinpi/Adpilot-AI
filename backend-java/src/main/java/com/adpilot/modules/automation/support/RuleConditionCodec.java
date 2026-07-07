package com.adpilot.modules.automation.support;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses the stored {@code condition_json} / {@code action_json} blobs of an
 * automation rule template into the pure {@link RuleCondition} / {@link RuleAction}
 * value types consumed by {@link RuleConditionEvaluator} (Req 25.3).
 *
 * <p>Kept separate from the evaluator so that the evaluation logic stays pure
 * (no JSON dependency). All parse failures surface as
 * {@link IllegalArgumentException} so callers can translate them into a domain
 * validation error.
 *
 * <h2>Condition JSON grammar</h2>
 * <pre>
 *   leaf      : { "metric": "&lt;name&gt;", "op": "&lt;gt|gte|lt|lte|eq|ne|symbol&gt;", "value": &lt;number&gt; }
 *   and       : { "op": "and", "conditions": [ &lt;condition&gt;, ... ] }
 *   or        : { "op": "or",  "conditions": [ &lt;condition&gt;, ... ] }
 *   not       : { "op": "not", "condition": &lt;condition&gt; }
 *   constant  : { "op": "true" | "false" }   or   { "const": &lt;boolean&gt; }
 * </pre>
 *
 * <h2>Action JSON grammar</h2>
 * <pre>
 *   { "type": "&lt;action-type&gt;", "params": { ... } }
 *   // or any object with a "type" field; the remaining fields become params.
 * </pre>
 */
public final class RuleConditionCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RuleConditionCodec() {
    }

    /**
     * Parse a {@code condition_json} string into a {@link RuleCondition}.
     *
     * @param json the stored condition JSON; must not be null/blank
     * @return the parsed condition tree
     * @throws IllegalArgumentException if the JSON is missing or malformed
     */
    public static RuleCondition parseCondition(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("condition_json must not be blank");
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("condition_json is not valid JSON: " + e.getMessage(), e);
        }
        return parseNode(root);
    }

    private static RuleCondition parseNode(JsonNode node) {
        if (node == null || node.isNull() || !node.isObject()) {
            throw new IllegalArgumentException("condition node must be a JSON object");
        }
        if (node.has("const") && node.get("const").isBoolean()) {
            return new RuleCondition.Constant(node.get("const").asBoolean());
        }
        String op = node.has("op") ? node.get("op").asText("") : "";
        switch (op.toLowerCase()) {
            case "and":
                return new RuleCondition.And(parseChildren(node));
            case "or":
                return new RuleCondition.Or(parseChildren(node));
            case "not": {
                JsonNode child = node.get("condition");
                if (child == null) {
                    throw new IllegalArgumentException("'not' requires a 'condition' field");
                }
                return new RuleCondition.Not(parseNode(child));
            }
            case "true":
                return RuleCondition.Constant.TRUE;
            case "false":
                return RuleCondition.Constant.FALSE;
            default:
                return parseComparison(node);
        }
    }

    private static List<RuleCondition> parseChildren(JsonNode node) {
        JsonNode arr = node.get("conditions");
        if (arr == null || !arr.isArray()) {
            throw new IllegalArgumentException("'" + node.get("op").asText() + "' requires a 'conditions' array");
        }
        List<RuleCondition> children = new ArrayList<>();
        for (JsonNode child : arr) {
            children.add(parseNode(child));
        }
        return children;
    }

    private static RuleCondition parseComparison(JsonNode node) {
        JsonNode metric = node.get("metric");
        JsonNode opNode = node.get("op");
        JsonNode value = node.get("value");
        if (metric == null || metric.isNull() || metric.asText().isBlank()) {
            throw new IllegalArgumentException("comparison requires a non-blank 'metric'");
        }
        if (opNode == null || opNode.isNull() || opNode.asText().isBlank()) {
            throw new IllegalArgumentException("comparison requires an 'op'");
        }
        if (value == null || value.isNull() || !value.isNumber()) {
            throw new IllegalArgumentException("comparison requires a numeric 'value'");
        }
        RuleComparator comparator = RuleComparator.from(opNode.asText());
        return new RuleCondition.Comparison(metric.asText(), comparator, value.decimalValue());
    }

    /**
     * Parse an {@code action_json} string into a {@link RuleAction}.
     *
     * @param json the stored action JSON; must not be null/blank
     * @return the parsed action
     * @throws IllegalArgumentException if the JSON is missing or malformed
     */
    public static RuleAction parseAction(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("action_json must not be blank");
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException("action_json is not valid JSON: " + e.getMessage(), e);
        }
        if (!root.isObject()) {
            throw new IllegalArgumentException("action_json must be a JSON object");
        }
        JsonNode typeNode = root.get("type");
        if (typeNode == null || typeNode.isNull() || typeNode.asText().isBlank()) {
            throw new IllegalArgumentException("action_json requires a non-blank 'type'");
        }
        Map<String, Object> params = new LinkedHashMap<>();
        JsonNode paramsNode = root.get("params");
        if (paramsNode != null && paramsNode.isObject()) {
            params = MAPPER.convertValue(paramsNode, new TypeReference<LinkedHashMap<String, Object>>() {});
        } else {
            // Treat every field other than "type" as a parameter.
            Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                if (!"type".equals(field.getKey())) {
                    params.put(field.getKey(), MAPPER.convertValue(field.getValue(), Object.class));
                }
            }
        }
        return new RuleAction(typeNode.asText(), params);
    }
}
