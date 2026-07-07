package com.adpilot.modules.advertising.hosting;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.modules.audit.service.AuditLogService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Persistent {@link CanaryRolloutService} backed by org-level rollout state.
 *
 * <p>When an org has canary disabled or no rollout row, canary is not a gate and
 * all stores proceed normally. When enabled, only stores present in
 * {@code hosting_canary_stores} may proceed to executable routing.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CanaryRolloutServiceImpl implements CanaryRolloutService {

    private final CanaryRolloutMapper rolloutMapper;
    private final CanaryStoreMapper storeMapper;
    private final AuditLogService auditLogService;

    @Override
    public boolean isCanaryEnabled(UUID orgId) {
        requireOrg(orgId);
        CanaryRolloutEntity rollout = rolloutMapper.selectById(orgId);
        return rollout != null && Boolean.TRUE.equals(rollout.getEnabled());
    }

    @Override
    public boolean isStoreInCanary(UUID orgId, UUID storeId) {
        requireOrg(orgId);
        requireStore(storeId);
        if (!isCanaryEnabled(orgId)) {
            return true;
        }
        return storeMapper.selectCount(new LambdaQueryWrapper<CanaryStoreEntity>()
                .eq(CanaryStoreEntity::getOrgId, orgId)
                .eq(CanaryStoreEntity::getStoreId, storeId)) > 0;
    }

    @Override
    public Set<UUID> getCanaryStores(UUID orgId) {
        requireOrg(orgId);
        return storeMapper.selectList(new LambdaQueryWrapper<CanaryStoreEntity>()
                        .eq(CanaryStoreEntity::getOrgId, orgId))
                .stream()
                .map(CanaryStoreEntity::getStoreId)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    @Override
    @Transactional
    public void addStoreToCanary(UUID orgId, UUID storeId, UUID actorId) {
        requireOrg(orgId);
        requireStore(storeId);
        long existing = storeMapper.selectCount(new LambdaQueryWrapper<CanaryStoreEntity>()
                .eq(CanaryStoreEntity::getOrgId, orgId)
                .eq(CanaryStoreEntity::getStoreId, storeId));
        if (existing == 0) {
            storeMapper.insert(CanaryStoreEntity.builder()
                    .id(UUID.randomUUID())
                    .orgId(orgId)
                    .storeId(storeId)
                    .createdBy(actorId)
                    .createdAt(LocalDateTime.now())
                    .build());
        }
        recordAudit(orgId, storeId, actorId, "ADD_STORE");
        log.info("Canary store added org={} store={} by actor={}", orgId, storeId, actorId);
    }

    @Override
    @Transactional
    public void removeStoreFromCanary(UUID orgId, UUID storeId, UUID actorId) {
        requireOrg(orgId);
        requireStore(storeId);
        storeMapper.delete(new LambdaQueryWrapper<CanaryStoreEntity>()
                .eq(CanaryStoreEntity::getOrgId, orgId)
                .eq(CanaryStoreEntity::getStoreId, storeId));
        recordAudit(orgId, storeId, actorId, "REMOVE_STORE");
        log.info("Canary store removed org={} store={} by actor={}", orgId, storeId, actorId);
    }

    @Override
    @Transactional
    public void enableCanary(UUID orgId, UUID actorId) {
        upsertRollout(orgId, true, actorId);
        recordAudit(orgId, orgId, actorId, "ENABLE");
        log.info("Canary rollout enabled for org={} by actor={}", orgId, actorId);
    }

    @Override
    @Transactional
    public void disableCanary(UUID orgId, UUID actorId) {
        upsertRollout(orgId, false, actorId);
        recordAudit(orgId, orgId, actorId, "DISABLE");
        log.info("Canary rollout disabled for org={} by actor={}", orgId, actorId);
    }

    private void upsertRollout(UUID orgId, boolean enabled, UUID actorId) {
        requireOrg(orgId);
        CanaryRolloutEntity existing = rolloutMapper.selectById(orgId);
        LocalDateTime now = LocalDateTime.now();
        if (existing == null) {
            rolloutMapper.insert(CanaryRolloutEntity.builder()
                    .orgId(orgId)
                    .enabled(enabled)
                    .createdBy(actorId)
                    .updatedBy(actorId)
                    .createdAt(now)
                    .updatedAt(now)
                    .build());
            return;
        }
        existing.setEnabled(enabled);
        existing.setUpdatedBy(actorId);
        existing.setUpdatedAt(now);
        rolloutMapper.updateById(existing);
    }

    private void recordAudit(UUID orgId, UUID entityId, UUID actorId, String action) {
        try {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("org_id", orgId.toString());
            details.put("action", action);
            if (entityId != null) {
                details.put("entity_id", entityId.toString());
            }
            auditLogService.createLog(actorId, orgId, action, "hosting_canary_rollout", entityId, details);
        } catch (Exception ex) {
            log.warn("Failed to record canary rollout audit org={} action={}: {}", orgId, action, ex.getMessage());
        }
    }

    private static void requireOrg(UUID orgId) {
        if (orgId == null) {
            throw new BusinessException(400, "HOSTING_INVALID_PARAMETER", "orgId must not be null");
        }
    }

    private static void requireStore(UUID storeId) {
        if (storeId == null) {
            throw new BusinessException(400, "HOSTING_INVALID_PARAMETER", "storeId must not be null");
        }
    }
}
