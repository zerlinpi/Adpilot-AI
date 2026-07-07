package com.adpilot.modules.store.service;

import com.adpilot.modules.store.vo.StoreVo;

import java.util.List;
import java.util.UUID;

/**
 * Outcome of a single-credential store discovery run (Req 6.1).
 *
 * <p>Discovery is idempotent: {@link #created} holds the internal stores that
 * were newly created for previously-unseen marketplaces (Req 6.1.2), while
 * {@link #reused} holds the existing internal stores that already corresponded
 * to a discovered marketplace and were reused rather than duplicated
 * (Req 6.1.3). Every store in either list carries the platform-reported
 * marketplace identifier (Req 6.1.4).</p>
 *
 * @param connectionId originating seller-account platform connection
 * @param platform     platform key the discovery ran against
 * @param created      stores newly created during this run
 * @param reused       existing stores reused during this run
 */
public record StoreDiscoveryResult(UUID connectionId,
                                   String platform,
                                   List<StoreVo> created,
                                   List<StoreVo> reused) {

    public StoreDiscoveryResult {
        created = created == null ? List.of() : List.copyOf(created);
        reused = reused == null ? List.of() : List.copyOf(reused);
    }

    /** Total number of marketplaces resolved to an internal store this run. */
    public int discoveredCount() {
        return created.size() + reused.size();
    }
}
