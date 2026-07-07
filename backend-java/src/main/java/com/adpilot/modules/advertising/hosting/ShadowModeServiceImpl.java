package com.adpilot.modules.advertising.hosting;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Implementation of {@link ShadowModeService} (Requirement 35.1).
 *
 * <p>Shadow mode is stored in the {@code hosting_configs} table as a boolean flag
 * in the JSON config payload at store scope. When active, the decision routing
 * pipeline persists decisions in {@code ai_decisions} but never creates Operations
 * or Outbox rows — no platform side effects occur.</p>
 *
 * <p>Validates: Requirements 35.1, 35.8.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShadowModeServiceImpl implements ShadowModeService {

    private static final String SHADOW_MODE_KEY = "shadow_mode";

    private final HostingConfigMapper hostingConfigMapper;

    @Override
    public boolean isInShadowMode(UUID storeId) {
        if (storeId == null) {
            return false;
        }

        HostingConfigEntity config = findStoreConfig(storeId);
        if (config == null || config.getConfig() == null) {
            return false;
        }

        return extractShadowMode(config.getConfig());
    }

    @Override
    @Transactional
    public void enableShadowMode(UUID storeId, UUID actorId) {
        if (storeId == null) {
            throw new IllegalArgumentException("storeId must not be null");
        }

        HostingConfigEntity config = findStoreConfig(storeId);

        if (config != null) {
            String updatedConfig = mergeShadowModeIntoConfig(config.getConfig(), true);
            config.setConfig(updatedConfig);
            config.setUpdatedBy(actorId);
            hostingConfigMapper.updateById(config);
        } else {
            HostingConfigEntity entity = HostingConfigEntity.builder()
                    .id(UUID.randomUUID())
                    .storeId(storeId)
                    .scope("store")
                    .scopeId(storeId)
                    .config("{\"" + SHADOW_MODE_KEY + "\":true}")
                    .createdBy(actorId)
                    .updatedBy(actorId)
                    .build();
            hostingConfigMapper.insert(entity);
        }

        log.info("Shadow mode enabled for store={} by actor={}", storeId, actorId);
    }

    @Override
    @Transactional
    public void disableShadowMode(UUID storeId, UUID actorId) {
        if (storeId == null) {
            throw new IllegalArgumentException("storeId must not be null");
        }

        HostingConfigEntity config = findStoreConfig(storeId);

        if (config != null) {
            String updatedConfig = mergeShadowModeIntoConfig(config.getConfig(), false);
            config.setConfig(updatedConfig);
            config.setUpdatedBy(actorId);
            hostingConfigMapper.updateById(config);
        }
        // If no config exists and we're disabling, nothing to do (already disabled)

        log.info("Shadow mode disabled for store={} by actor={}", storeId, actorId);
    }

    // --- Internal helpers ---

    private HostingConfigEntity findStoreConfig(UUID storeId) {
        LambdaQueryWrapper<HostingConfigEntity> query = new LambdaQueryWrapper<>();
        query.eq(HostingConfigEntity::getStoreId, storeId)
                .eq(HostingConfigEntity::getScope, "store")
                .eq(HostingConfigEntity::getScopeId, storeId);
        return hostingConfigMapper.selectOne(query);
    }

    /**
     * Extracts the shadow_mode boolean from a JSON config string.
     * Returns false if not present or not parseable.
     */
    static boolean extractShadowMode(String config) {
        if (config == null || config.isBlank()) {
            return false;
        }
        String marker = "\"" + SHADOW_MODE_KEY + "\"";
        int idx = config.indexOf(marker);
        if (idx < 0) {
            return false;
        }
        int colonIdx = config.indexOf(':', idx + marker.length());
        if (colonIdx < 0) {
            return false;
        }
        // Look for true/false after the colon
        String afterColon = config.substring(colonIdx + 1).trim();
        return afterColon.startsWith("true");
    }

    /**
     * Merges the shadow_mode flag into an existing JSON config string.
     */
    static String mergeShadowModeIntoConfig(String existingConfig, boolean enabled) {
        String value = enabled ? "true" : "false";
        if (existingConfig == null || existingConfig.isBlank()) {
            return "{\"" + SHADOW_MODE_KEY + "\":" + value + "}";
        }

        String marker = "\"" + SHADOW_MODE_KEY + "\"";
        int idx = existingConfig.indexOf(marker);
        if (idx >= 0) {
            // Replace existing value
            int colonIdx = existingConfig.indexOf(':', idx + marker.length());
            // Find the end of the boolean value (next comma or closing brace)
            int valueStart = colonIdx + 1;
            while (valueStart < existingConfig.length()
                    && Character.isWhitespace(existingConfig.charAt(valueStart))) {
                valueStart++;
            }
            int valueEnd = valueStart;
            while (valueEnd < existingConfig.length()
                    && existingConfig.charAt(valueEnd) != ','
                    && existingConfig.charAt(valueEnd) != '}') {
                valueEnd++;
            }
            return existingConfig.substring(0, valueStart) + value + existingConfig.substring(valueEnd);
        } else {
            // Add shadow_mode to the JSON object
            if (existingConfig.endsWith("}")) {
                String prefix = existingConfig.substring(0, existingConfig.length() - 1);
                if (prefix.trim().equals("{")) {
                    return "{\"" + SHADOW_MODE_KEY + "\":" + value + "}";
                }
                return prefix + ",\"" + SHADOW_MODE_KEY + "\":" + value + "}";
            }
            return "{\"" + SHADOW_MODE_KEY + "\":" + value + "}";
        }
    }
}
