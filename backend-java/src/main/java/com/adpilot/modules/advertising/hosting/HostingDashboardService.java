package com.adpilot.modules.advertising.hosting;

import com.adpilot.modules.advertising.vo.HostingAnalyticsVo;
import com.adpilot.modules.advertising.vo.HostingDashboardSummaryVo;
import com.adpilot.modules.advertising.vo.HostingDecisionDetailVo;
import com.adpilot.modules.advertising.vo.HostingDecisionVo;
import com.adpilot.modules.advertising.vo.HostingHealthVo;

import java.util.List;
import java.util.UUID;

/**
 * Query service backing the real-data hosting dashboard, decision list/explanation, historical
 * analytics, and dependency-health endpoints (Requirements 11, 27, 29, 30.5).
 *
 * <p>All "today"/period boundaries are computed in the store's Marketplace_Timezone (Req 11.2,
 * 27.2). Estimated savings and all analytics impact figures are derived from
 * {@code effect_attributions.estimated_incremental_impact} and are always labelled as estimates
 * (Req 11.3, 27.3, 29.3) — never presented as realized values.</p>
 *
 * <p>Cross-organization isolation is enforced by {@link HostingOrgIsolationGuard} at the controller
 * boundary before these methods run (Req 39).</p>
 */
public interface HostingDashboardService {

    /**
     * Build the dashboard summary cards for a store (Req 11.1, 27.1). Counts are filtered by the
     * store and "today" in the marketplace timezone; estimated savings are computed from effect
     * attribution and labelled as estimates.
     *
     * @param storeId the (already org-verified) store id
     * @return the summary view
     */
    HostingDashboardSummaryVo getSummary(UUID storeId);

    /**
     * List the most recent AI decisions for a store from {@code ai_decisions} (Req 11.4), joined to
     * their promoted Operation for the current SyncState and proposed change.
     *
     * @param storeId the (already org-verified) store id
     * @param limit   maximum number of decisions to return (bounded internally)
     * @return the decision list, newest first
     */
    List<HostingDecisionVo> listDecisions(UUID storeId, int limit);

    /**
     * Build the Decision_Explanation detail for a single decision (Req 11.5, 13), including the
     * immutable snapshot and any effect-attribution results.
     *
     * @param decision the (already org-verified) decision entity
     * @return the decision detail view
     */
    HostingDecisionDetailVo getDecisionDetail(AiDecisionEntity decision);

    /**
     * Compute historical analytics over {@code ai_decisions} joined to {@code operations} for the
     * given period (Req 29). Rates are effective/attempted; the per-engine breakdown partitions the
     * totals; impact aggregates are estimates.
     *
     * @param storeId the (already org-verified) store id
     * @param period  one of {@code 7d}, {@code 30d}, {@code 90d}
     * @return the analytics view
     */
    HostingAnalyticsVo getAnalytics(UUID storeId, String period);

    /**
     * Report the health of each external dependency (amazon_api, inventory_service, redis, feishu)
     * as healthy/degraded/unavailable (Req 30.5).
     *
     * @return the dependency-health view
     */
    HostingHealthVo getHealth();
}
