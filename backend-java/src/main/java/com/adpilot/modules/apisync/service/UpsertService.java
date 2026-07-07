package com.adpilot.modules.apisync.service;

import com.adpilot.modules.apisync.model.MappedRecord;
import com.adpilot.modules.apisync.model.SyncContext;
import com.adpilot.modules.apisync.model.UpsertResult;

/**
 * Persists a {@link MappedRecord} to its internal table idempotently (Req 1.2),
 * using {@code external_entity_mappings} as the idempotency anchor.
 *
 * <p>Behavior:</p>
 * <ul>
 *   <li>When the record's external id already maps (a mapping exists for
 *       {@code (store_id, platform, internal_entity_type, external_entity_id)}),
 *       the linked internal record is updated and {@link UpsertResult.Outcome#UPDATED}
 *       is returned (Req 1.2.1, 1.2.3).</li>
 *   <li>When the external id is unmapped, exactly one internal record is created
 *       together with its mapping and {@link UpsertResult.Outcome#CREATED} is
 *       returned (Req 1.2.2, 1.2.3).</li>
 *   <li>When the platform reports the record as removed/cancelled, the existing
 *       internal record is status-marked cancelled/inactive rather than
 *       physically deleted (Req 1.2.4).</li>
 * </ul>
 *
 * <p>Processing the same external records repeatedly yields the same set of
 * internal records and mappings (idempotent).</p>
 */
public interface UpsertService {

    /**
     * Idempotently create or update the internal record for a mapped external
     * record.
     *
     * @param ctx    the per-run sync context; supplies {@code storeId},
     *               {@code platform}, and {@code connectionId}
     * @param record the mapped record to persist
     * @return whether the internal record was created or updated, with its id
     */
    UpsertResult upsert(SyncContext ctx, MappedRecord record);
}
