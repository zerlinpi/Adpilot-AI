package com.adpilot.modules.rbac.service.impl;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.rbac.PlatformFamily;
import com.adpilot.modules.rbac.dto.CreateStoreGroupCommand;
import com.adpilot.modules.rbac.entity.StoreGroupEntity;
import com.adpilot.modules.rbac.mapper.StoreGroupMapper;
import com.adpilot.modules.rbac.service.StoreGroupService;
import com.adpilot.modules.rbac.vo.StoreGroupVo;
import com.adpilot.modules.store.entity.StoreEntity;
import com.adpilot.modules.store.mapper.StoreMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Default {@link StoreGroupService} backed by {@link StoreGroupMapper} and
 * {@link StoreMapper}. All operations are scoped to the caller's organization
 * (platform-workspace-rbac Req 10, 11).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StoreGroupServiceImpl implements StoreGroupService {

    private static final int MAX_NAME_LENGTH = 100;
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final StoreGroupMapper storeGroupMapper;
    private final StoreMapper storeMapper;

    @Override
    @Transactional
    public StoreGroupVo create(CreateStoreGroupCommand cmd) {
        UUID orgId = requireCurrentOrgId();
        PlatformFamily family = requireStoreGroupFamily(cmd != null ? cmd.getPlatformFamily() : null);
        String name = validateName(cmd != null ? cmd.getName() : null);

        assertNameAvailable(orgId, family, name, null);

        StoreGroupEntity entity = StoreGroupEntity.builder()
                .orgId(orgId)
                .name(name)
                .platformFamily(family.getCode())
                .isDefault(false)
                .createdBy(parseUuidOrNull(SecurityUtils.getCurrentUserIdOrNull()))
                .build();
        storeGroupMapper.insert(entity);
        log.info("Store group created: id={}, name={}, family={}", entity.getId(), name, family.getCode());
        return toVo(entity);
    }

    @Override
    @Transactional
    public StoreGroupVo rename(UUID id, String name) {
        UUID orgId = requireCurrentOrgId();
        StoreGroupEntity entity = requireGroup(id, orgId);
        String newName = validateName(name);

        PlatformFamily family = PlatformFamily.fromCode(entity.getPlatformFamily());
        assertNameAvailable(orgId, family, newName, id);

        entity.setName(newName);
        entity.setUpdatedAt(LocalDateTime.now());
        storeGroupMapper.updateById(entity);
        log.info("Store group renamed: id={}, name={}", id, newName);
        return toVo(entity);
    }

    @Override
    @Transactional
    public void assignStore(UUID storeId, UUID storeGroupId) {
        UUID orgId = requireCurrentOrgId();
        if (storeId == null || storeGroupId == null) {
            throw new BusinessException(400, "STORE_GROUP_ASSIGN_INVALID",
                    "Store id and store group id are required");
        }

        StoreEntity store = storeMapper.selectById(storeId);
        if (store == null || !orgId.equals(store.getOrgId())) {
            throw new BusinessException(404, "STORE_NOT_FOUND", "Store not found: " + storeId);
        }

        StoreGroupEntity group = requireGroup(storeGroupId, orgId);

        // Platform-family must match the store's platform (Req 10.6, 11.4).
        if (store.getPlatformFamily() == null
                || !store.getPlatformFamily().equalsIgnoreCase(group.getPlatformFamily())) {
            throw new BusinessException(400, "STORE_GROUP_PLATFORM_MISMATCH",
                    "Store group platform family '" + group.getPlatformFamily()
                            + "' does not match the store's platform family '"
                            + store.getPlatformFamily() + "'");
        }

        store.setStoreGroupId(group.getId());
        store.setUpdatedAt(LocalDateTime.now());
        storeMapper.updateById(store);
        log.info("Store {} reassigned to store group {}", storeId, storeGroupId);
    }

    @Override
    public List<StoreGroupVo> listByFamily(PlatformFamily family) {
        UUID orgId = requireCurrentOrgId();
        if (family == null) {
            throw new BusinessException(400, "STORE_GROUP_FAMILY_REQUIRED", "Platform family is required");
        }
        LambdaQueryWrapper<StoreGroupEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StoreGroupEntity::getOrgId, orgId)
                .eq(StoreGroupEntity::getPlatformFamily, family.getCode())
                .orderByAsc(StoreGroupEntity::getName);
        return storeGroupMapper.selectList(wrapper).stream()
                .map(this::toVo)
                .collect(Collectors.toList());
    }

    @Override
    public UUID defaultGroupFor(PlatformFamily family) {
        UUID orgId = requireCurrentOrgId();
        if (family == null) {
            throw new BusinessException(400, "STORE_GROUP_FAMILY_REQUIRED", "Platform family is required");
        }
        LambdaQueryWrapper<StoreGroupEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StoreGroupEntity::getOrgId, orgId)
                .eq(StoreGroupEntity::getPlatformFamily, family.getCode())
                .eq(StoreGroupEntity::getIsDefault, true)
                .last("LIMIT 1");
        StoreGroupEntity entity = storeGroupMapper.selectOne(wrapper);
        if (entity == null) {
            throw new BusinessException(404, "STORE_GROUP_DEFAULT_MISSING",
                    "No default store group for platform family: " + family.getCode());
        }
        return entity.getId();
    }

    // ------------------------------------------------------------------
    // Validation helpers
    // ------------------------------------------------------------------

    /** Validate a Store_Group name: 1-100 characters (Req 10.1, 10.4). */
    private String validateName(String rawName) {
        String name = rawName == null ? null : rawName.trim();
        if (name == null || name.isEmpty()) {
            throw new BusinessException(400, "STORE_GROUP_NAME_INVALID",
                    "Store group name is required");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new BusinessException(400, "STORE_GROUP_NAME_INVALID",
                    "Store group name must not exceed " + MAX_NAME_LENGTH + " characters");
        }
        return name;
    }

    /** A Store_Group family must be {@code amazon} or {@code independent_site} (Req 10.1). */
    private PlatformFamily requireStoreGroupFamily(PlatformFamily family) {
        if (family == null) {
            throw new BusinessException(400, "STORE_GROUP_FAMILY_REQUIRED", "Platform family is required");
        }
        if (!family.isStoreGroupFamily()) {
            throw new BusinessException(400, "STORE_GROUP_PLATFORM_INVALID",
                    "Platform family '" + family.getCode() + "' cannot have store groups");
        }
        return family;
    }

    /**
     * Reject a name that duplicates an existing group within the same org and
     * platform family (Req 10.4). {@code excludeId} skips the group being renamed.
     */
    private void assertNameAvailable(UUID orgId, PlatformFamily family, String name, UUID excludeId) {
        LambdaQueryWrapper<StoreGroupEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StoreGroupEntity::getOrgId, orgId)
                .eq(StoreGroupEntity::getPlatformFamily, family.getCode())
                .eq(StoreGroupEntity::getName, name);
        if (excludeId != null) {
            wrapper.ne(StoreGroupEntity::getId, excludeId);
        }
        if (storeGroupMapper.selectCount(wrapper) > 0) {
            throw new BusinessException(400, "STORE_GROUP_NAME_CONFLICT",
                    "A store group named '" + name + "' already exists in this platform family");
        }
    }

    private StoreGroupEntity requireGroup(UUID id, UUID orgId) {
        if (id == null) {
            throw new BusinessException(404, "STORE_GROUP_NOT_FOUND", "Store group not found");
        }
        StoreGroupEntity entity = storeGroupMapper.selectById(id);
        if (entity == null || !orgId.equals(entity.getOrgId())) {
            throw new BusinessException(404, "STORE_GROUP_NOT_FOUND", "Store group not found: " + id);
        }
        return entity;
    }

    private UUID requireCurrentOrgId() {
        if (!SecurityUtils.isAuthenticated()) {
            throw new BusinessException(401, "AUTH_REQUIRED", "Authentication is required");
        }
        UUID orgId = parseUuidOrNull(SecurityUtils.getCurrentOrgId());
        if (orgId == null) {
            throw new BusinessException(403, "ORG_CONTEXT_REQUIRED", "Organization context is required");
        }
        return orgId;
    }

    private StoreGroupVo toVo(StoreGroupEntity entity) {
        return StoreGroupVo.builder()
                .id(entity.getId() != null ? entity.getId().toString() : null)
                .orgId(entity.getOrgId() != null ? entity.getOrgId().toString() : null)
                .name(entity.getName())
                .platformFamily(entity.getPlatformFamily())
                .isDefault(entity.getIsDefault())
                .createdBy(entity.getCreatedBy() != null ? entity.getCreatedBy().toString() : null)
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().format(FORMATTER) : null)
                .build();
    }

    /** Safely parse a UUID, returning null for null/blank/non-UUID values. */
    private static UUID parseUuidOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
