package com.adpilot.modules.advertising.hosting;

import com.adpilot.modules.advertising.dto.HostingConfigRequest;
import com.adpilot.modules.advertising.vo.HostingConfigVo;

import java.util.UUID;

/**
 * Service managing per-scope hosting configuration persisted in {@code hosting_configs}
 * (Req 12, 21).
 *
 * <p>Configuration is stored as a JSON payload keyed by the snake_case fields named in
 * Requirement 21.1 ({@code active_phase}, {@code default_personality},
 * {@code execution_mode}, {@code auto_execute_threshold}, {@code notification_preferences},
 * {@code boundary_overrides}, plus {@code shadow_mode} / {@code emergency_auto_action_enabled}).
 * All writes are validated on the backend (Req 12.6, 21.3, 21.4):</p>
 * <ul>
 *   <li>{@code default_personality} must be a valid machine-value enum;</li>
 *   <li>{@code execution_mode} must be a valid enum value;</li>
 *   <li>{@code auto_execute_threshold} must lie within [0.0, 1.0];</li>
 *   <li>{@code boundary_overrides} must only tighten the resolved higher-level boundary
 *       per each limit's comparison semantics, and satisfy cross-field ordering.</li>
 * </ul>
 */
public interface HostingConfigService {

    /**
     * Load the hosting configuration for a given scope.
     *
     * @param storeId the owning store
     * @param scope   the scope type: {@code store}, {@code goal}, or {@code campaign}
     * @param scopeId the identifier within the scope (store/goal/campaign id)
     * @return the persisted configuration, with null value fields when no row exists
     */
    HostingConfigVo getConfig(UUID storeId, String scope, UUID scopeId);

    /**
     * Validate and persist the store-level hosting configuration (Req 21.2).
     *
     * <p>Only the non-null fields of {@code request} are overlaid onto any existing
     * configuration; absent fields are left unchanged. The change is recorded in the
     * audit trail (Req 12.4).</p>
     *
     * @param storeId the store being configured
     * @param request the proposed configuration values
     * @param actorId the authenticated user making the change
     * @return the saved configuration as confirmation (Req 12.2)
     * @throws com.adpilot.common.exception.BusinessException with HTTP 400 when a value
     *         is invalid (Req 21.3, 21.4)
     */
    HostingConfigVo saveStoreConfig(UUID storeId, HostingConfigRequest request, UUID actorId);

    /**
     * Validate and persist a <em>campaign-scoped</em> hosting configuration
     * (scope {@code campaign}), used by the per-product AI ad creation
     * orchestration to attach a campaign's Execution_Mode at creation time
     * (Req 1.6).
     *
     * <p>Behaves like {@link #saveStoreConfig} but writes the row at the
     * {@code campaign} scope keyed by {@code campaignId}: only the non-null
     * fields of {@code request} are overlaid onto any existing campaign-scope
     * configuration, and the same field-level validation (personality /
     * execution-mode / phase enums, {@code auto_execute_threshold} range, and
     * only-tighten boundary overrides) is applied.</p>
     *
     * @param storeId    the owning store
     * @param campaignId the campaign the configuration is scoped to
     * @param request    the proposed configuration values
     * @param actorId    the authenticated user making the change (may be {@code null})
     * @return the saved configuration as confirmation
     * @throws com.adpilot.common.exception.BusinessException with HTTP 400 when a value
     *         is invalid (Req 21.3, 21.4)
     */
    HostingConfigVo saveCampaignConfig(UUID storeId, UUID campaignId, HostingConfigRequest request, UUID actorId);
}
