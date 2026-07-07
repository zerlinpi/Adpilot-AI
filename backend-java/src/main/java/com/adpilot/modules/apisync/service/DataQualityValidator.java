package com.adpilot.modules.apisync.service;

import com.adpilot.modules.apisync.model.MappedRecord;
import com.adpilot.modules.apisync.model.ValidationResult;

/**
 * Validates a {@link MappedRecord} against the configured required-field rules
 * and type/format rules for its entity type (Req 1.4.1).
 *
 * <p>The validator is pure and side-effect free: it inspects the mapped field
 * values and reports any violations as a {@link ValidationResult}. It does not
 * persist anything and does not decide what happens to a failing record.</p>
 *
 * <p>The sync pipeline ({@code SyncJobRunner}) uses the result to exclude an
 * invalid record from upsert and to record exactly one {@code sync_record_errors}
 * entry per invalid record, while continuing to process the remaining records
 * (Req 1.4.2, 1.4.3).</p>
 *
 * <h2>Supported entity types</h2>
 * <ul>
 *   <li>{@code "order"} &rarr; {@code channel_orders}</li>
 *   <li>{@code "product"} &rarr; {@code channel_products}</li>
 * </ul>
 *
 * <p>An unknown entity type is itself a validation failure rather than an
 * exception, so the runner can treat it like any other invalid record.</p>
 */
public interface DataQualityValidator {

    /**
     * Validate a single mapped record.
     *
     * @param entityType the internal entity type (e.g. {@code "order"},
     *                   {@code "product"}); selects the rule set to apply
     * @param record     the mapped record to validate
     * @return a {@link ValidationResult} that is {@link ValidationResult#valid()
     *         valid} with no errors when every rule passes, or invalid carrying
     *         one {@link com.adpilot.modules.apisync.model.FieldError} per failing
     *         rule
     */
    ValidationResult validate(String entityType, MappedRecord record);
}
