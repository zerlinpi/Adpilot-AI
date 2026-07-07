package com.adpilot.modules.advertising.hosting;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Default implementation of {@link ExecutionModeResolver} (Req 7.2).
 *
 * <p>Resolves the effective execution mode for a campaign by checking the
 * {@code hosting_configs} table at three levels in precedence order:
 * <ol>
 *   <li>Campaign-level override (scope='campaign', scope_id=campaignId)</li>
 *   <li>Goal-level override (scope='goal', scope_id=goalId)</li>
 *   <li>Store-level override (scope='store', scope_id=storeId)</li>
 * </ol>
 *
 * <p>The first level that carries a recognized {@code execution_mode} value in its
 * JSON config wins. When no level defines a mode, the system default
 * {@link ExecutionMode#OBSERVE_ONLY} applies — new stores start safe.
 *
 * <p>The core {@link #resolve(String, String, String)} method is PURE (no I/O) so it
 * can be property-tested in isolation. The loading overload
 * {@link #resolveForCampaign(UUID, UUID, UUID)} fetches configs from the database
 * and delegates to the pure method.
 *
 * <p>Validates: Requirements 7.2, 7.3.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExecutionModeResolverImpl implements ExecutionModeResolver {

    private static final String EXECUTION_MODE_KEY = "execution_mode";

    private final HostingConfigMapper hostingConfigMapper;
    private final ObjectMapper objectMapper;

    /**
     * Pure resolution of the effective execution mode from three raw level values.
     * The first non-null, recognized value wins; otherwise defaults to OBSERVE_ONLY.
     *
     * @param campaignMode the campaign-level execution mode string (may be {@code null})
     * @param goalMode     the goal-level execution mode string (may be {@code null})
     * @param storeMode    the store-level execution mode string (may be {@code null})
     * @return the effective {@link ExecutionMode}; never {@code null}
     */
    @Override
    public ExecutionMode resolve(String campaignMode, String goalMode, String storeMode) {
        ExecutionMode mode = ExecutionMode.parse(campaignMode);
        if (mode != null) {
            return mode;
        }
        mode = ExecutionMode.parse(goalMode);
        if (mode != null) {
            return mode;
        }
        mode = ExecutionMode.parse(storeMode);
        if (mode != null) {
            return mode;
        }
        return ExecutionMode.DEFAULT;
    }

    /**
     * Resolve the effective execution mode for a campaign by looking up hosting
     * configs at campaign, goal, and store levels from the database.
     *
     * @param campaignId the campaign id
     * @param goalId     the campaign's goal id (may be {@code null} if unassigned)
     * @param storeId    the store id (required)
     * @return the effective {@link ExecutionMode}; never {@code null}
     */
    @Override
    public ExecutionMode resolveForCampaign(UUID campaignId, UUID goalId, UUID storeId) {
        String campaignMode = loadExecutionMode("campaign", campaignId);
        String goalMode = goalId != null ? loadExecutionMode("goal", goalId) : null;
        String storeMode = loadExecutionMode("store", storeId);

        return resolve(campaignMode, goalMode, storeMode);
    }

    /**
     * Load the execution_mode value from the hosting_configs table for a given scope.
     *
     * @param scope   the scope type ('store', 'goal', or 'campaign')
     * @param scopeId the id of the scoped entity
     * @return the raw execution_mode string from the config JSON, or {@code null}
     */
    private String loadExecutionMode(String scope, UUID scopeId) {
        if (scopeId == null) {
            return null;
        }
        LambdaQueryWrapper<HostingConfigEntity> query = new LambdaQueryWrapper<HostingConfigEntity>()
                .eq(HostingConfigEntity::getScope, scope)
                .eq(HostingConfigEntity::getScopeId, scopeId);

        HostingConfigEntity entity = hostingConfigMapper.selectOne(query);
        if (entity == null || entity.getConfig() == null || entity.getConfig().isBlank()) {
            return null;
        }

        return extractExecutionMode(entity.getConfig());
    }

    /**
     * Extract the execution_mode field from a JSON config string.
     *
     * @param configJson the JSON string from the config column
     * @return the execution_mode value, or {@code null} if absent or unparseable
     */
    private String extractExecutionMode(String configJson) {
        try {
            JsonNode root = objectMapper.readTree(configJson);
            JsonNode modeNode = root.get(EXECUTION_MODE_KEY);
            if (modeNode != null && modeNode.isTextual()) {
                return modeNode.asText();
            }
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse hosting config JSON for execution_mode extraction: {}",
                    e.getMessage());
        }
        return null;
    }
}
