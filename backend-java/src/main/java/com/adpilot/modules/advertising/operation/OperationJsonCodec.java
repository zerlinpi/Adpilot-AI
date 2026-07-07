package com.adpilot.modules.advertising.operation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * Serializes the JSON-column payloads of an {@code Operation_Record} ({@code before_value},
 * {@code after_value}, {@code platform_result}, {@code ai_decision}) to/from their stored
 * {@code String} form.
 *
 * <p>The {@code operations} table stores these as JSON columns and {@link OperationEntity} maps them
 * as {@code String}. Callers supply raw DOMAIN values — a {@link java.math.BigDecimal} bid, an
 * Object_Status string, a map of changed fields, an {@link AiDecision} — and this codec produces the
 * canonical JSON text. Because it serializes the value directly, a scalar domain value yields valid
 * JSON (a number {@code 1.23}, a quoted string {@code "PAUSED"}) and an object yields a JSON object;
 * callers therefore pass domain values, NOT pre-serialized JSON strings (a pre-serialized string
 * would be double-encoded).</p>
 *
 * <p>It delegates to the application's shared {@link ObjectMapper} bean so JSON formatting (date
 * format, {@code JavaTimeModule}) is consistent with the rest of the system.</p>
 */
@Component
public class OperationJsonCodec {

    private final ObjectMapper objectMapper;

    public OperationJsonCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Serialize a raw domain value to its canonical JSON text.
     *
     * @param value the domain value (scalar, collection, map, or bean); may be {@code null}
     * @return the JSON text, or {@code null} when {@code value} is {@code null}
     * @throws OperationRecordValidationException if the value cannot be serialized
     */
    public String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new OperationRecordValidationException(
                    "Failed to serialize Operation_Record JSON payload of type "
                            + value.getClass().getName(), e);
        }
    }

    /**
     * Deserialize stored JSON text back into the given type. Provided for read paths and tests.
     *
     * @param json the stored JSON text; may be {@code null}
     * @param type the target type
     * @return the deserialized value, or {@code null} when {@code json} is {@code null}
     * @throws OperationRecordValidationException if the text cannot be deserialized
     */
    public <T> T fromJson(String json, Class<T> type) {
        if (json == null) {
            return null;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new OperationRecordValidationException(
                    "Failed to deserialize Operation_Record JSON payload into " + type.getName(), e);
        }
    }
}
