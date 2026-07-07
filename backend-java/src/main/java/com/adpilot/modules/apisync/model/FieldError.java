package com.adpilot.modules.apisync.model;

/**
 * A single data-quality violation found while validating a mapped record
 * (Req 1.4). One {@code FieldError} corresponds to one failing required-field
 * or type/format rule.
 *
 * @param field   internal field the rule applies to ({@code null} for
 *                record-level errors)
 * @param code    machine-readable error code (e.g. "required", "type",
 *                "format")
 * @param message human-readable explanation of the violation
 */
public record FieldError(String field, String code, String message) {

    public static FieldError of(String field, String code, String message) {
        return new FieldError(field, code, message);
    }

    public static FieldError required(String field) {
        return new FieldError(field, "required", "Field '" + field + "' is required");
    }

    public static FieldError type(String field, String expectedType) {
        return new FieldError(field, "type",
                "Field '" + field + "' must be of type " + expectedType);
    }
}
