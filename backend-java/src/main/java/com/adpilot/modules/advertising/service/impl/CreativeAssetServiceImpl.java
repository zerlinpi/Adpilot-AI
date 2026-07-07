package com.adpilot.modules.advertising.service.impl;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.common.security.CurrentUser;
import com.adpilot.common.security.DataScopeService;
import com.adpilot.common.security.ScopeTarget;
import com.adpilot.common.utils.FileStorageUtils;
import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.advertising.converter.CreativeAssetConverter;
import com.adpilot.modules.advertising.dto.CreativeAssetCreateRequest;
import com.adpilot.modules.advertising.entity.CreativeAssetEntity;
import com.adpilot.modules.advertising.mapper.CreativeAssetMapper;
import com.adpilot.modules.advertising.service.CreativeAssetService;
import com.adpilot.modules.advertising.support.CreativeAssetFilter;
import com.adpilot.modules.advertising.support.CreativeAssetView;
import com.adpilot.modules.advertising.vo.CreativeAssetVo;
import com.adpilot.modules.user.entity.User;
import com.adpilot.modules.user.mapper.UserMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Creative Asset Library implementation (Req 29). Uploads persist the binary via
 * {@link FileStorageUtils} and record metadata in {@code creative_assets};
 * search is delegated to the pure {@link CreativeAssetFilter} predicate so its
 * soundness/completeness matches the contract Property 6 pins down. Creator
 * names are resolved from {@code users} for display and creator-search.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CreativeAssetServiceImpl implements CreativeAssetService {

    private final CreativeAssetMapper creativeAssetMapper;
    private final UserMapper userMapper;
    private final DataScopeService dataScopeService;

    /** Root directory uploaded creative assets are stored under. Configurable. */
    @Value("${adpilot.creative-assets.storage-dir:./data/creative-assets}")
    private String storageDir;

    /** Store scope target — creative_assets carries no owner column. */
    private static final ScopeTarget ASSET_SCOPE = ScopeTarget.store("store_id");

    /** Allowed creative categories (生活方式图/场景图/高清图组/营销宣传图). */
    private static final Set<String> ASSET_TYPES = Set.of("lifestyle", "scene", "hd_group", "marketing");

    private static CurrentUser scopeUser() {
        return SecurityUtils.isAuthenticated() ? SecurityUtils.getCurrentUser() : null;
    }

    @Override
    public List<CreativeAssetVo> listAssets(String storeId, String assetType, String search) {
        QueryWrapper<CreativeAssetEntity> wrapper = new QueryWrapper<>();
        if (storeId != null && !storeId.isBlank()) {
            wrapper.eq("store_id", parseUuid(storeId, "storeId"));
        }
        CurrentUser user = scopeUser();
        if (user != null) {
            dataScopeService.applyScope(wrapper, ASSET_SCOPE, user);
        }
        wrapper.orderByDesc("created_at");

        List<CreativeAssetEntity> entities = creativeAssetMapper.selectList(wrapper);
        Map<UUID, String> creatorNames = resolveCreators(entities);

        // Search is decided by the pure CreativeAssetFilter (Req 29.3), keeping
        // the predicate's behaviour identical to what Property 6 pins down.
        CreativeAssetFilter filter = CreativeAssetFilter.builder()
                .storeId(storeId)
                .assetType(assetType)
                .search(search)
                .build();

        List<CreativeAssetVo> result = new ArrayList<>();
        for (CreativeAssetEntity entity : entities) {
            String creator = creatorNames.get(entity.getCreatedBy());
            CreativeAssetView view = CreativeAssetConverter.toView(entity, creator);
            if (filter.matches(view)) {
                result.add(CreativeAssetConverter.toVo(entity, creator));
            }
        }
        return result;
    }

    @Override
    @Transactional
    public CreativeAssetVo uploadAsset(CreativeAssetCreateRequest request, MultipartFile file) {
        UUID storeUuid = parseUuid(request.getStoreId(), "storeId");

        String name = request.getName() != null ? request.getName().trim() : "";
        if (name.isEmpty()) {
            throw new BusinessException("VALIDATION_ERROR", "Asset name is required");
        }
        if (name.length() > 255) {
            throw new BusinessException("VALIDATION_ERROR", "Asset name must be at most 255 characters");
        }
        if (file == null || file.isEmpty()) {
            throw new BusinessException("VALIDATION_ERROR", "An asset file is required");
        }

        String assetType = normalizeAssetType(request.getAssetType());
        String mediaKind = resolveMediaKind(request.getMediaKind(), file);
        String storageUrl = store(file);

        UUID createdBy = currentUserUuid();

        CreativeAssetEntity entity = CreativeAssetEntity.builder()
                .storeId(storeUuid)
                .name(name)
                .assetType(assetType)
                .mediaKind(mediaKind)
                .storageUrl(storageUrl)
                .asin(blankToNull(request.getAsin()))
                .tags(request.getTags() != null ? request.getTags() : new ArrayList<>())
                .createdBy(createdBy)
                .build();
        creativeAssetMapper.insert(entity);

        log.info("Creative asset uploaded: id={}, name={}, store={}", entity.getId(), name, storeUuid);
        return CreativeAssetConverter.toVo(entity, resolveCreator(createdBy));
    }

    /** Persist the uploaded bytes via FileStorageUtils and return the stored location. */
    private String store(MultipartFile file) {
        try {
            String ext = extensionOf(file.getOriginalFilename());
            String filename = FileStorageUtils.storeFile(file.getBytes(), storageDir, ext);
            return "/uploads/creative-assets/" + filename;
        } catch (IOException ex) {
            log.error("Failed to store creative asset file", ex);
            throw new BusinessException("STORAGE_ERROR", "Failed to store the uploaded asset");
        }
    }

    private String normalizeAssetType(String assetType) {
        if (assetType == null || assetType.isBlank()) {
            return "lifestyle";
        }
        String normalized = assetType.trim().toLowerCase();
        if (!ASSET_TYPES.contains(normalized)) {
            throw new BusinessException("VALIDATION_ERROR",
                    "Unsupported asset type: " + assetType + " (expected one of " + ASSET_TYPES + ")");
        }
        return normalized;
    }

    /** Use the supplied kind when valid; otherwise infer from the file content type. */
    private String resolveMediaKind(String mediaKind, MultipartFile file) {
        if (mediaKind != null && !mediaKind.isBlank()) {
            String normalized = mediaKind.trim().toLowerCase();
            if ("image".equals(normalized) || "video".equals(normalized)) {
                return normalized;
            }
        }
        String contentType = file.getContentType();
        if (contentType != null && contentType.toLowerCase().startsWith("video")) {
            return "video";
        }
        return "image";
    }

    private static String extensionOf(String originalName) {
        if (originalName == null) {
            return "bin";
        }
        int dot = originalName.lastIndexOf('.');
        if (dot >= 0 && dot < originalName.length() - 1) {
            return originalName.substring(dot + 1);
        }
        return "bin";
    }

    /** Resolve creator display names for the given assets in a single query. */
    private Map<UUID, String> resolveCreators(List<CreativeAssetEntity> entities) {
        Map<UUID, String> names = new HashMap<>();
        List<UUID> ids = entities.stream()
                .map(CreativeAssetEntity::getCreatedBy)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
        if (ids.isEmpty()) {
            return names;
        }
        for (User user : userMapper.selectBatchIds(ids)) {
            if (user.getId() != null) {
                names.put(user.getId(), user.getName());
            }
        }
        return names;
    }

    private String resolveCreator(UUID userId) {
        if (userId == null) {
            return null;
        }
        User user = userMapper.selectById(userId);
        return user != null ? user.getName() : null;
    }

    private UUID currentUserUuid() {
        String id = SecurityUtils.getCurrentUserIdOrNull();
        if (id == null) {
            return null;
        }
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static String blankToNull(String v) {
        return (v == null || v.isBlank()) ? null : v.trim();
    }

    private UUID parseUuid(String value, String field) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new BusinessException("INVALID_ID", "Invalid " + field + ": " + value);
        }
    }
}
