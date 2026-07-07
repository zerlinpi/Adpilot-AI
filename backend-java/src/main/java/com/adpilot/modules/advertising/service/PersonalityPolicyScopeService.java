package com.adpilot.modules.advertising.service;

import com.adpilot.modules.advertising.entity.PersonalityPolicyEntity;

import java.util.UUID;

/**
 * Scoped personality policy resolution and lifecycle management (Requirement 38).
 *
 * <p>Policies are keyed by {@code (scope, scope_id, personality, rule_version)} with
 * a {@code UNIQUE} constraint in the database. At most ONE version may be {@code active}
 * per {@code (scope, scope_id, personality)} at any time (Req 38.2).</p>
 *
 * <p>Scope resolution follows the most-specific-first precedence:
 * {@code campaign → goal → store → organization → system} (Req 38.3).</p>
 *
 * <p>All activation/deactivation changes are audited with before/after state (Req 38.6).</p>
 */
public interface PersonalityPolicyScopeService {

    /** Scope level constants in precedence order (most specific first). */
    String SCOPE_CAMPAIGN = "campaign";
    String SCOPE_GOAL = "goal";
    String SCOPE_STORE = "store";
    String SCOPE_ORGANIZATION = "organization";
    String SCOPE_SYSTEM = "system";

    /**
     * Resolves the effective personality policy for the given personality and scope context.
     * Walks the scope hierarchy from most-specific to least-specific, returning the first
     * active policy found (Req 38.3).
     *
     * @param personality  the resolved AI personality machine value
     * @param campaignId   campaign-level scope id (nullable)
     * @param goalId       goal-level scope id (nullable)
     * @param storeId      store-level scope id (nullable)
     * @param orgId        organization-level scope id (nullable)
     * @return the most-specific active policy; never {@code null} (falls back to system)
     * @throws com.adpilot.common.exception.BusinessException if no policy exists at any level
     */
    PersonalityPolicyEntity resolvePolicy(String personality, UUID campaignId, UUID goalId,
                                          UUID storeId, UUID orgId);

    /**
     * Activates the given policy version. If another version is currently active for the
     * same {@code (scope, scope_id, personality)}, it is deactivated first. The change is
     * recorded in the audit log (Req 38.2, 38.6).
     *
     * @param policyId the id of the policy row to activate
     * @throws com.adpilot.common.exception.BusinessException if the policy does not exist
     */
    void activatePolicy(UUID policyId);

    /**
     * Deactivates the given policy. The change is recorded in the audit log (Req 38.6).
     *
     * @param policyId the id of the policy row to deactivate
     * @throws com.adpilot.common.exception.BusinessException if the policy does not exist
     */
    void deactivatePolicy(UUID policyId);
}
