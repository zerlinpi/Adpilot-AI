package com.adpilot.modules.apisync.model;

import java.util.List;

/**
 * Result of validating a mapped record against the configured required-field
 * and type/format rules for its entity type (Req 1.4). A record is excluded
 * from upsert when {@link #valid()} is {@code false}.
 *
 * @param valid  whether the record satisfies all rules
 * @param errors the violations found; empty when {@code valid} is {@code true}
 */
public record ValidationResult(boolean valid, List<FieldError> errors) {

    public ValidationResult {
        errors = errors == null ? List.of() : List.copyOf(errors);
    }

    public static ValidationResult ok() {
        return new ValidationResult(true, List.of());
    }

    public static ValidationResult invalid(List<FieldError> errors) {
        return new ValidationResult(false, errors);
    }

    public static ValidationResult of(List<FieldError> errors) {
        return errors == null || errors.isEmpty() ? ok() : invalid(errors);
    }
}
