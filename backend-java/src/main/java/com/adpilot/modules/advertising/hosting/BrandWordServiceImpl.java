package com.adpilot.modules.advertising.hosting;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.modules.advertising.dto.BrandWordCreateRequest;
import com.adpilot.modules.advertising.vo.BrandWordVo;
import com.adpilot.modules.audit.service.AuditLogService;
import com.adpilot.modules.store.entity.StoreEntity;
import com.adpilot.modules.store.mapper.StoreMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Default {@link BrandWordService} backed by {@code brand_word_lists}.
 *
 * <p>Mutations invalidate the {@link BrandWordProtectionService} cache so the V3 engine
 * sees changes promptly, and are recorded in the audit log (Req 22.6).</p>
 *
 * <p>Validates: Requirements 22.1, 22.2, 22.6.</p>
 */
@Slf4j
@Service
public class BrandWordServiceImpl implements BrandWordService {

    private static final String MATCH_EXACT = "exact";
    private static final String MATCH_CONTAINS = "contains";

    private final BrandWordMapper brandWordMapper;
    private final BrandWordProtectionService brandWordProtectionService;
    private final StoreMapper storeMapper;
    private final AuditLogService auditLogService;

    public BrandWordServiceImpl(BrandWordMapper brandWordMapper,
                                BrandWordProtectionService brandWordProtectionService,
                                StoreMapper storeMapper,
                                AuditLogService auditLogService) {
        this.brandWordMapper = brandWordMapper;
        this.brandWordProtectionService = brandWordProtectionService;
        this.storeMapper = storeMapper;
        this.auditLogService = auditLogService;
    }

    @Override
    public List<BrandWordVo> listBrandWords(UUID storeId) {
        requireNonNull(storeId, "Store ID must not be null");
        return brandWordMapper.selectByStoreId(storeId).stream()
                .map(this::toVo)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public BrandWordVo addBrandWord(UUID storeId, BrandWordCreateRequest request, UUID actorId) {
        requireNonNull(storeId, "Store ID must not be null");
        if (request == null || request.getWord() == null || request.getWord().isBlank()) {
            throw new BusinessException(400, "HOSTING_INVALID_PARAMETER", "Brand word must not be blank");
        }

        String word = request.getWord().trim();
        if (word.length() > 255) {
            throw new BusinessException(400, "HOSTING_INVALID_PARAMETER",
                    "Brand word must be at most 255 characters");
        }
        String matchType = normalizeMatchType(request.getMatchType());

        BrandWordEntity entity = BrandWordEntity.builder()
                .id(UUID.randomUUID())
                .storeId(storeId)
                .word(word)
                .matchType(matchType)
                .createdBy(actorId)
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        brandWordMapper.insert(entity);

        brandWordProtectionService.invalidateCache(storeId);
        recordAudit(storeId, actorId, "CREATE", entity);

        log.info("Brand word added for store={} word='{}' matchType={} by actor={}",
                storeId, word, matchType, actorId);
        return toVo(entity);
    }

    @Override
    @Transactional
    public void deleteBrandWord(UUID storeId, UUID brandWordId, UUID actorId) {
        requireNonNull(storeId, "Store ID must not be null");
        requireNonNull(brandWordId, "Brand word ID must not be null");

        BrandWordEntity entity = brandWordMapper.selectById(brandWordId);
        if (entity == null || entity.getStoreId() == null || !entity.getStoreId().equals(storeId)) {
            throw new BusinessException(404, "HOSTING_BRAND_WORD_NOT_FOUND",
                    "Brand word not found for this store");
        }

        brandWordMapper.deleteById(brandWordId);

        brandWordProtectionService.invalidateCache(storeId);
        recordAudit(storeId, actorId, "DELETE", entity);

        log.info("Brand word removed for store={} id={} by actor={}", storeId, brandWordId, actorId);
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Helpers
    // ────────────────────────────────────────────────────────────────────────────

    private String normalizeMatchType(String matchType) {
        if (matchType == null || matchType.isBlank()) {
            return MATCH_EXACT;
        }
        String normalized = matchType.trim().toLowerCase(Locale.ROOT);
        if (!normalized.equals(MATCH_EXACT) && !normalized.equals(MATCH_CONTAINS)) {
            throw new BusinessException(400, "HOSTING_INVALID_PARAMETER",
                    "Invalid match_type '" + matchType + "'. Must be one of: exact, contains.");
        }
        return normalized;
    }

    private void recordAudit(UUID storeId, UUID actorId, String action, BrandWordEntity entity) {
        try {
            UUID orgId = resolveOrgId(storeId);
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("store_id", storeId.toString());
            details.put("word", entity.getWord());
            details.put("match_type", entity.getMatchType());
            auditLogService.createLog(actorId, orgId, action, "brand_word", entity.getId(), details);
        } catch (Exception ex) {
            log.warn("Failed to record brand word audit for store={}: {}", storeId, ex.getMessage());
        }
    }

    private UUID resolveOrgId(UUID storeId) {
        StoreEntity store = storeMapper.selectById(storeId);
        return store == null ? null : store.getOrgId();
    }

    private BrandWordVo toVo(BrandWordEntity entity) {
        return BrandWordVo.builder()
                .id(entity.getId() == null ? null : entity.getId().toString())
                .storeId(entity.getStoreId() == null ? null : entity.getStoreId().toString())
                .word(entity.getWord())
                .matchType(entity.getMatchType())
                .createdAt(entity.getCreatedAt() == null ? null : entity.getCreatedAt().toString())
                .build();
    }

    private static void requireNonNull(Object value, String message) {
        if (value == null) {
            throw new BusinessException(400, "HOSTING_INVALID_PARAMETER", message);
        }
    }
}
