package com.adpilot.modules.advertising.hosting;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.common.security.CurrentUser;
import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.advertising.entity.CampaignEntity;
import com.adpilot.modules.advertising.entity.GoalEntity;
import com.adpilot.modules.advertising.entity.OperationEntity;
import com.adpilot.modules.advertising.mapper.CampaignMapper;
import com.adpilot.modules.advertising.mapper.GoalMapper;
import com.adpilot.modules.advertising.mapper.OperationMapper;
import com.adpilot.modules.store.entity.StoreEntity;
import com.adpilot.modules.store.mapper.StoreMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Cross-organization isolation guard for every hosting API and job (Requirement 39).
 *
 * <p>Stores carry {@code org_id} ({@link StoreEntity#getOrgId()}) and every hosting
 * entity is org-scopeable via {@code store_id} &rarr; store &rarr; {@code org_id}. This
 * guard resolves every inbound id ({@code storeId}, {@code campaignId}, {@code goalId},
 * {@code operationId}, {@code runId}, {@code decisionId}) to a store and verifies that
 * store belongs to the <em>caller's current organization</em> BEFORE the existing
 * {@link com.adpilot.common.security.DataScopeService} store-level checks run
 * (Requirement 39.1 / 39.3).</p>
 *
 * <p>The org check is layered <em>in addition to</em> (not instead of) data-scope, so
 * both boundaries must hold (Requirement 39.5). On any mismatch this guard fails closed:
 * a non-existent id yields HTTP 404 and a cross-org id yields HTTP 403, neither of which
 * echoes any resource detail back to the caller, so there is no cross-org data leak
 * (Requirement 39.1).</p>
 *
 * <p>This component performs <em>resolution and org assertion only</em>. It does not
 * replace data-scope; callers invoke this guard first, then proceed to the existing
 * {@code DataScopeService} guards as before.</p>
 *
 * <p>Validates: Requirements 39.1, 39.2, 39.3, 39.4, 39.5.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HostingOrgIsolationGuard {

    private final StoreMapper storeMapper;
    private final CampaignMapper campaignMapper;
    private final GoalMapper goalMapper;
    private final OperationMapper operationMapper;
    private final OptimizationRunMapper optimizationRunMapper;
    private final AiDecisionMapper aiDecisionMapper;

    // ------------------------------------------------------------------
    // Public resolution API (one method per inbound id type, Req 39.1)
    // ------------------------------------------------------------------

    /**
     * Resolve a store and verify it belongs to the caller's organization.
     *
     * @param storeId inbound store id
     * @return the resolved {@link StoreEntity}
     * @throws BusinessException 404 when the store does not exist, 403 when it
     *         belongs to a different organization
     */
    public StoreEntity resolveStoreInCallerOrg(UUID storeId) {
        return assertStoreInCallerOrg(storeId);
    }

    /**
     * Resolve a campaign, follow {@code campaign} &rarr; {@code store} &rarr; {@code org},
     * and verify it belongs to the caller's organization.
     *
     * @throws BusinessException 404 when not found, 403 when cross-org
     */
    public CampaignEntity resolveCampaignInCallerOrg(UUID campaignId) {
        if (campaignId == null) {
            throw notFound();
        }
        CampaignEntity campaign = campaignMapper.selectById(campaignId);
        if (campaign == null) {
            throw notFound();
        }
        assertStoreInCallerOrg(campaign.getStoreId());
        return campaign;
    }

    /**
     * Resolve a goal, follow {@code goal} &rarr; {@code store} &rarr; {@code org}, and
     * verify it belongs to the caller's organization.
     *
     * @throws BusinessException 404 when not found, 403 when cross-org
     */
    public GoalEntity resolveGoalInCallerOrg(UUID goalId) {
        if (goalId == null) {
            throw notFound();
        }
        GoalEntity goal = goalMapper.selectById(goalId);
        if (goal == null) {
            throw notFound();
        }
        assertStoreInCallerOrg(goal.getStoreId());
        return goal;
    }

    /**
     * Resolve an operation, follow {@code operation} &rarr; {@code store} &rarr;
     * {@code org}, and verify it belongs to the caller's organization.
     *
     * @throws BusinessException 404 when not found, 403 when cross-org
     */
    public OperationEntity resolveOperationInCallerOrg(UUID operationId) {
        if (operationId == null) {
            throw notFound();
        }
        OperationEntity operation = operationMapper.selectById(operationId);
        if (operation == null) {
            throw notFound();
        }
        assertStoreInCallerOrg(operation.getStoreId());
        return operation;
    }

    /**
     * Resolve an optimization run, follow {@code run} &rarr; {@code store} &rarr;
     * {@code org}, and verify it belongs to the caller's organization.
     *
     * @throws BusinessException 404 when not found, 403 when cross-org
     */
    public OptimizationRunEntity resolveRunInCallerOrg(UUID runId) {
        if (runId == null) {
            throw notFound();
        }
        OptimizationRunEntity run = optimizationRunMapper.selectById(runId);
        if (run == null) {
            throw notFound();
        }
        assertStoreInCallerOrg(run.getStoreId());
        return run;
    }

    /**
     * Resolve an AI decision, follow {@code decision} &rarr; {@code store} &rarr;
     * {@code org}, and verify it belongs to the caller's organization.
     *
     * @throws BusinessException 404 when not found, 403 when cross-org
     */
    public AiDecisionEntity resolveDecisionInCallerOrg(UUID decisionId) {
        if (decisionId == null) {
            throw notFound();
        }
        AiDecisionEntity decision = aiDecisionMapper.selectById(decisionId);
        if (decision == null) {
            throw notFound();
        }
        assertStoreInCallerOrg(decision.getStoreId());
        return decision;
    }

    // ------------------------------------------------------------------
    // Core store/org assertion
    // ------------------------------------------------------------------

    /**
     * Load a store by id and assert it belongs to the caller's organization.
     *
     * <p>Fails closed: a missing store id, a non-existent store, or a store whose
     * {@code org_id} differs from the caller's org all raise a {@link BusinessException}
     * carrying no resource detail (Req 39.1). A store that legitimately has no
     * {@code org_id} can never be matched to any caller and is therefore denied.</p>
     *
     * @param storeId the store id to resolve (may be {@code null})
     * @return the resolved {@link StoreEntity} known to belong to the caller's org
     */
    private StoreEntity assertStoreInCallerOrg(UUID storeId) {
        UUID callerOrgId = requireCallerOrgId();
        if (storeId == null) {
            throw notFound();
        }
        StoreEntity store = storeMapper.selectById(storeId);
        if (store == null) {
            throw notFound();
        }

        UUID storeOrgId = store.getOrgId();
        if (storeOrgId == null || !storeOrgId.equals(callerOrgId)) {
            // Cross-org (or org-less) store: deny with no detail, do not leak the resource.
            throw forbidden();
        }
        return store;
    }

    /**
     * Resolve the caller's current organization id, failing closed when there is no
     * authenticated principal or the principal carries no parseable org id.
     */
    private UUID requireCallerOrgId() {
        CurrentUser user = SecurityUtils.getCurrentUser();
        UUID orgId = parseUuidOrNull(user.getOrgId());
        if (orgId == null) {
            // An authenticated caller with no resolvable org can access nothing org-scoped.
            throw forbidden();
        }
        return orgId;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static BusinessException notFound() {
        return new BusinessException(404, "NOT_FOUND", "Resource not found");
    }

    private static BusinessException forbidden() {
        return new BusinessException(403, "FORBIDDEN", "Access to the requested resource is denied");
    }

    private static UUID parseUuidOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
