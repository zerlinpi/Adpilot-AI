package com.adpilot.modules.advertising.operation;

import com.adpilot.modules.advertising.mapper.OperationPendingChangeMapper;
import com.adpilot.modules.advertising.support.PendingOverlayRow;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Query-layer implementation of the {@link PendingOverlayService}.
 *
 * <p>It reads the open pending-change rows whose owning Operation is in an Unsettled_State for the
 * entity (via {@link OperationPendingChangeMapper#findOpenUnsettledChanges}) and composes them onto
 * the entity's confirmed values. No second physical column per field is involved: the pending value
 * is materialized from the Operation's {@code after_value} (Req 7.1, 7.2).</p>
 *
 * <p>{@link #composeOverlay(Map, List)} carries the entire decision and is kept pure and
 * collaborator-free so the Pending_Overlay invariant (Property 21) can be exercised directly.</p>
 *
 * <p>Validates: Requirements 7.3, 7.6, 7.7.</p>
 */
@Service
public class PendingOverlayServiceImpl implements PendingOverlayService {

    private final OperationPendingChangeMapper pendingChangeMapper;
    private final OperationJsonCodec jsonCodec;

    public PendingOverlayServiceImpl(OperationPendingChangeMapper pendingChangeMapper,
                                     OperationJsonCodec jsonCodec) {
        this.pendingChangeMapper = pendingChangeMapper;
        this.jsonCodec = jsonCodec;
    }

    @Override
    public Map<String, OverlayField> overlayFor(String entityType,
                                                String entityId,
                                                Map<String, Object> confirmedValues) {
        List<String> unsettledStates = new ArrayList<>();
        for (SyncState state : SyncState.UNSETTLED) {
            unsettledStates.add(OperationMachineValues.toValue(state));
        }
        List<String> fields = new ArrayList<>(confirmedValues.keySet());
        List<PendingOverlayRow> rows =
                pendingChangeMapper.findOpenUnsettledChanges(entityType, entityId, unsettledStates, fields);
        return composeOverlay(confirmedValues, rows);
    }

    /**
     * Compose the overlay from an entity's confirmed values and the candidate pending-change rows.
     *
     * <p>For each supplied field, the latest <em>Unsettled_State</em> candidate row wins (by the
     * owning Operation's {@code created_at}, newest first). When such a row exists, the field's
     * overlay carries the confirmed value plus that Operation's decoded pending value and Sync_State
     * (Req 7.3, 7.6). When no Unsettled_State row targets the field — there is none, or every
     * candidate is in a settled state — only the confirmed value is surfaced (Req 7.7). The
     * confirmed value is passed through untouched in every case.</p>
     *
     * <p>The method defensively re-checks {@link SyncState#isUnsettled()} on each candidate so a
     * settled row can never leak a pending value, independent of how the rows were fetched.</p>
     *
     * @param confirmedValues the entity's confirmed value per writable field (defines coverage)
     * @param candidateRows   pending-change rows for the entity; may carry any Sync_State
     * @return the {@link OverlayField} per supplied field
     */
    public Map<String, OverlayField> composeOverlay(Map<String, Object> confirmedValues,
                                                    List<PendingOverlayRow> candidateRows) {
        Map<String, PendingOverlayRow> latestUnsettledByField = new HashMap<>();
        if (candidateRows != null) {
            for (PendingOverlayRow row : candidateRows) {
                SyncState state = OperationMachineValues.toSyncState(row.getSyncState());
                if (state == null || !state.isUnsettled()) {
                    continue;
                }
                PendingOverlayRow incumbent = latestUnsettledByField.get(row.getField());
                if (incumbent == null || isNewer(row, incumbent)) {
                    latestUnsettledByField.put(row.getField(), row);
                }
            }
        }

        Map<String, OverlayField> overlay = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : confirmedValues.entrySet()) {
            String field = entry.getKey();
            Object confirmed = entry.getValue();
            PendingOverlayRow latest = latestUnsettledByField.get(field);
            if (latest == null) {
                overlay.put(field, OverlayField.confirmed(confirmed));
            } else {
                Object pendingValue = jsonCodec.fromJson(latest.getAfterValue(), Object.class);
                SyncState pendingState = OperationMachineValues.toSyncState(latest.getSyncState());
                overlay.put(field, OverlayField.withPending(confirmed, pendingValue, pendingState));
            }
        }
        return overlay;
    }

    /** A candidate is newer than the incumbent when its owning Operation was created later. */
    private static boolean isNewer(PendingOverlayRow candidate, PendingOverlayRow incumbent) {
        LocalDateTime candidateAt = candidate.getCreatedAt();
        LocalDateTime incumbentAt = incumbent.getCreatedAt();
        if (candidateAt == null) {
            return false;
        }
        if (incumbentAt == null) {
            return true;
        }
        return candidateAt.isAfter(incumbentAt);
    }
}
