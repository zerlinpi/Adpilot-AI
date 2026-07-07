package com.adpilot.modules.keyword.service.impl;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.modules.advertising.entity.AdGroupEntity;
import com.adpilot.modules.advertising.entity.KeywordEntity;
import com.adpilot.modules.advertising.entity.NegativeKeywordEntity;
import com.adpilot.modules.advertising.mapper.AdGroupMapper;
import com.adpilot.modules.advertising.mapper.KeywordMapper;
import com.adpilot.modules.advertising.mapper.NegativeKeywordMapper;
import com.adpilot.modules.keyword.converter.KeywordLibraryConverter;
import com.adpilot.modules.keyword.dto.KeywordLibraryCreateRequest;
import com.adpilot.modules.keyword.entity.KeywordInsightEntity;
import com.adpilot.modules.keyword.entity.KeywordLibraryEntity;
import com.adpilot.modules.keyword.entity.KeywordLibraryItemEntity;
import com.adpilot.modules.keyword.mapper.KeywordInsightMapper;
import com.adpilot.modules.keyword.mapper.KeywordLibraryItemMapper;
import com.adpilot.modules.keyword.mapper.KeywordLibraryMapper;
import com.adpilot.modules.keyword.service.KeywordLibraryService;
import com.adpilot.modules.keyword.vo.KeywordConfigVo;
import com.adpilot.modules.keyword.vo.KeywordLibraryVo;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Keyword Library management and keyword-recommendation actions (Req 27).
 *
 * <p>The recommendation harvest / negate actions reuse the existing keyword and
 * negative-keyword domains: harvesting inserts a positive {@link KeywordEntity}
 * (resolving an ad group for the recommendation's campaign) or, when no ad-group
 * context is available, records the keyword in a store harvest library; negating
 * inserts a store-scoped {@link NegativeKeywordEntity}. Both close the underlying
 * keyword insight.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KeywordLibraryServiceImpl implements KeywordLibraryService {

    private final KeywordLibraryMapper libraryMapper;
    private final KeywordLibraryItemMapper itemMapper;
    private final KeywordInsightMapper insightMapper;
    private final KeywordMapper keywordMapper;
    private final NegativeKeywordMapper negativeKeywordMapper;
    private final AdGroupMapper adGroupMapper;

    /** Library-type prefix for store-level keyword-config seed libraries. */
    private static final String SEED_TYPE_PREFIX = "seed_";
    private static final String SEED_BRAND = "seed_brand";
    private static final String SEED_CATEGORY = "seed_category";
    private static final String SEED_COMPETITOR_BRAND = "seed_competitor_brand";
    private static final String SEED_COMPETITOR_ASIN = "seed_competitor_asin";

    @Override
    public List<KeywordLibraryVo> listLibraries(String storeId) {
        LambdaQueryWrapper<KeywordLibraryEntity> wrapper = new LambdaQueryWrapper<>();
        if (storeId != null && !storeId.isBlank()) {
            wrapper.eq(KeywordLibraryEntity::getStoreId, parseUuid(storeId, "storeId"));
        }
        // Exclude the store-level keyword-config seed libraries (seed_*): those are
        // surfaced on the 关键词配置 tab, not the 词库 tab.
        wrapper.notLikeRight(KeywordLibraryEntity::getLibraryType, SEED_TYPE_PREFIX);
        wrapper.orderByDesc(KeywordLibraryEntity::getCreatedAt);

        List<KeywordLibraryEntity> libraries = libraryMapper.selectList(wrapper);
        if (libraries.isEmpty()) {
            return List.of();
        }

        Map<UUID, List<KeywordLibraryItemEntity>> itemsByLibrary = loadItems(
                libraries.stream().map(KeywordLibraryEntity::getId).collect(Collectors.toList()));
        return KeywordLibraryConverter.toVoList(libraries, itemsByLibrary);
    }

    @Override
    @Transactional
    public KeywordLibraryVo createLibrary(KeywordLibraryCreateRequest request) {
        UUID storeId = parseUuid(request.getStoreId(), "storeId");

        KeywordLibraryEntity library = KeywordLibraryEntity.builder()
                .storeId(storeId)
                .name(request.getName())
                .libraryType(normalizeLibraryType(request.getLibraryType()))
                .scheduleCron(request.getScheduleCron())
                .build();
        libraryMapper.insert(library);

        List<KeywordLibraryItemEntity> items = buildItems(library.getId(), request.getProductIds(), request.getKeywords());
        for (KeywordLibraryItemEntity item : items) {
            itemMapper.insert(item);
        }

        log.info("Keyword library created: id={}, name={}, items={}", library.getId(), library.getName(), items.size());
        return KeywordLibraryConverter.toVo(library, items);
    }

    @Override
    @Transactional
    public Map<String, Object> harvestRecommendation(String recommendationId, String userId) {
        KeywordInsightEntity insight = loadInsight(recommendationId);
        UUID createdBy = parseUuidOrNull(userId);

        Map<String, Object> result = new HashMap<>();
        result.put("recommendationId", recommendationId);
        result.put("action", "harvest");
        result.put("keywordText", insight.getText());

        AdGroupEntity adGroup = resolveAdGroup(insight);
        if (adGroup != null) {
            String matchType = matchTypeForAction(insight.getRecommendedAction());
            KeywordEntity keyword = KeywordEntity.builder()
                    .campaignId(adGroup.getCampaignId())
                    .adGroupId(adGroup.getId())
                    .storeId(insight.getStoreId())
                    .keywordText(insight.getText())
                    .matchType(matchType)
                    .status("enabled")
                    .bid(resolveHarvestBid(adGroup))
                    .createdBy(createdBy)
                    .updatedBy(createdBy)
                    .build();
            keywordMapper.insert(keyword);
            result.put("keywordId", keyword.getId().toString());
            result.put("matchType", matchType);
            result.put("target", "keyword");
        } else {
            // No ad-group context: record the harvested keyword in a store harvest library.
            KeywordLibraryEntity library = ensureDefaultLibrary(insight.getStoreId(), "harvest");
            KeywordLibraryItemEntity item = KeywordLibraryItemEntity.builder()
                    .libraryId(library.getId())
                    .productId(insight.getProductId())
                    .keywordText(insight.getText())
                    .build();
            itemMapper.insert(item);
            result.put("libraryId", library.getId().toString());
            result.put("target", "library");
        }

        closeInsight(insight, "applied");
        log.info("Keyword recommendation harvested: insightId={}, target={}", recommendationId, result.get("target"));
        return result;
    }

    @Override
    @Transactional
    public Map<String, Object> negateRecommendation(String recommendationId, String userId) {
        KeywordInsightEntity insight = loadInsight(recommendationId);
        UUID createdBy = parseUuidOrNull(userId);

        NegativeKeywordEntity negative = NegativeKeywordEntity.builder()
                .campaignId(insight.getCampaignId())
                .storeId(insight.getStoreId())
                .keywordText(insight.getText())
                .matchType("negativeExact")
                .level(insight.getCampaignId() != null ? "campaign" : "store")
                .source("keyword_recommendation")
                .status("enabled")
                .createdBy(createdBy)
                .build();
        negativeKeywordMapper.insert(negative);

        closeInsight(insight, "applied");

        Map<String, Object> result = new HashMap<>();
        result.put("recommendationId", recommendationId);
        result.put("action", "negate");
        result.put("keywordText", insight.getText());
        result.put("negativeKeywordId", negative.getId().toString());
        log.info("Keyword recommendation negated: insightId={}, negativeKeywordId={}", recommendationId, negative.getId());
        return result;
    }

    @Override
    public KeywordConfigVo getKeywordConfig(String storeId) {
        UUID store = parseUuid(storeId, "storeId");
        return KeywordConfigVo.builder()
                .brand(loadConfigTerms(store, SEED_BRAND))
                .category(loadConfigTerms(store, SEED_CATEGORY))
                .competitorBrand(loadConfigTerms(store, SEED_COMPETITOR_BRAND))
                .competitorAsin(loadConfigTerms(store, SEED_COMPETITOR_ASIN))
                .build();
    }

    @Override
    @Transactional
    public KeywordConfigVo addKeywordConfig(String storeId, String category, List<String> terms) {
        UUID store = parseUuid(storeId, "storeId");
        String libraryType = seedTypeForCategory(category);

        if (terms != null && !terms.isEmpty()) {
            KeywordLibraryEntity library = ensureDefaultLibrary(store, libraryType);

            // Existing terms (case-insensitive) so adds are idempotent.
            List<String> existing = loadConfigTerms(store, libraryType);
            java.util.Set<String> seen = existing.stream()
                    .map(s -> s.toLowerCase(java.util.Locale.ROOT))
                    .collect(Collectors.toCollection(java.util.HashSet::new));

            for (String raw : terms) {
                if (raw == null || raw.isBlank()) {
                    continue;
                }
                String text = raw.trim();
                if (seen.add(text.toLowerCase(java.util.Locale.ROOT))) {
                    itemMapper.insert(KeywordLibraryItemEntity.builder()
                            .libraryId(library.getId())
                            .keywordText(text)
                            .build());
                }
            }
            log.info("Keyword config updated: store={}, category={}, type={}", storeId, category, libraryType);
        }

        return getKeywordConfig(storeId);
    }

    // ───────────────────────── helpers ─────────────────────────

    /** Maps a frontend config category to its seed library type. */
    private String seedTypeForCategory(String category) {
        if (category == null) {
            throw new BusinessException("INVALID_CATEGORY", "Category is required");
        }
        String c = category.trim();
        return switch (c) {
            case "brand" -> SEED_BRAND;
            case "category" -> SEED_CATEGORY;
            case "competitorBrand", "competitor_brand" -> SEED_COMPETITOR_BRAND;
            case "competitorAsin", "competitor_asin" -> SEED_COMPETITOR_ASIN;
            default -> throw new BusinessException("INVALID_CATEGORY", "Unsupported config category: " + category);
        };
    }

    /** Loads the distinct seed terms for a store + seed library type, preserving insertion order. */
    private List<String> loadConfigTerms(UUID storeId, String libraryType) {
        LambdaQueryWrapper<KeywordLibraryEntity> libWrapper = new LambdaQueryWrapper<>();
        libWrapper.eq(KeywordLibraryEntity::getStoreId, storeId)
                .eq(KeywordLibraryEntity::getLibraryType, libraryType);
        List<KeywordLibraryEntity> libraries = libraryMapper.selectList(libWrapper);
        if (libraries.isEmpty()) {
            return new ArrayList<>();
        }
        List<UUID> libraryIds = libraries.stream().map(KeywordLibraryEntity::getId).collect(Collectors.toList());

        QueryWrapper<KeywordLibraryItemEntity> itemWrapper = new QueryWrapper<>();
        itemWrapper.in("library_id", libraryIds).orderByAsc("created_at");
        List<KeywordLibraryItemEntity> items = itemMapper.selectList(itemWrapper);

        List<String> terms = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (KeywordLibraryItemEntity item : items) {
            String text = item.getKeywordText();
            if (text != null && !text.isBlank() && seen.add(text.toLowerCase(java.util.Locale.ROOT))) {
                terms.add(text.trim());
            }
        }
        return terms;
    }

    /** Maps an insight's recommended action to a harvested keyword match type. */
    private String matchTypeForAction(String recommendedAction) {
        if (recommendedAction == null) {
            return "exact";
        }
        return switch (recommendedAction) {
            case "add_phrase" -> "phrase";
            case "add_broad" -> "broad";
            default -> "exact"; // add_exact / unknown -> exact
        };
    }

    /** Resolves a non-zero starting bid for a harvested keyword from the ad group's default bid. */
    private BigDecimal resolveHarvestBid(AdGroupEntity adGroup) {
        if (adGroup != null && adGroup.getDefaultBid() != null
                && adGroup.getDefaultBid().compareTo(BigDecimal.ZERO) > 0) {
            return adGroup.getDefaultBid();
        }
        return new BigDecimal("0.75");
    }

    private List<KeywordLibraryItemEntity> buildItems(UUID libraryId, List<String> productIds, List<String> keywords) {
        List<KeywordLibraryItemEntity> items = new ArrayList<>();
        if (keywords == null || keywords.isEmpty()) {
            return items;
        }
        List<UUID> products = new ArrayList<>();
        if (productIds != null) {
            for (String pid : productIds) {
                if (pid != null && !pid.isBlank()) {
                    products.add(parseUuid(pid, "productId"));
                }
            }
        }
        for (String keyword : keywords) {
            if (keyword == null || keyword.isBlank()) {
                continue;
            }
            String text = keyword.trim();
            if (products.isEmpty()) {
                items.add(KeywordLibraryItemEntity.builder()
                        .libraryId(libraryId)
                        .keywordText(text)
                        .build());
            } else {
                for (UUID product : products) {
                    items.add(KeywordLibraryItemEntity.builder()
                            .libraryId(libraryId)
                            .productId(product)
                            .keywordText(text)
                            .build());
                }
            }
        }
        return items;
    }

    private Map<UUID, List<KeywordLibraryItemEntity>> loadItems(List<UUID> libraryIds) {
        if (libraryIds == null || libraryIds.isEmpty()) {
            return Map.of();
        }
        QueryWrapper<KeywordLibraryItemEntity> wrapper = new QueryWrapper<>();
        wrapper.in("library_id", libraryIds);
        List<KeywordLibraryItemEntity> items = itemMapper.selectList(wrapper);

        Map<UUID, List<KeywordLibraryItemEntity>> grouped = new HashMap<>();
        for (KeywordLibraryItemEntity item : items) {
            grouped.computeIfAbsent(item.getLibraryId(), k -> new ArrayList<>()).add(item);
        }
        return grouped;
    }

    private KeywordLibraryEntity ensureDefaultLibrary(UUID storeId, String libraryType) {
        LambdaQueryWrapper<KeywordLibraryEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(KeywordLibraryEntity::getStoreId, storeId)
                .eq(KeywordLibraryEntity::getLibraryType, libraryType)
                .orderByAsc(KeywordLibraryEntity::getCreatedAt)
                .last("LIMIT 1");
        KeywordLibraryEntity existing = libraryMapper.selectOne(wrapper);
        if (existing != null) {
            return existing;
        }
        KeywordLibraryEntity library = KeywordLibraryEntity.builder()
                .storeId(storeId)
                .name("harvest".equals(libraryType) ? "收割词库" : "默认词库")
                .libraryType(libraryType)
                .build();
        libraryMapper.insert(library);
        return library;
    }

    private AdGroupEntity resolveAdGroup(KeywordInsightEntity insight) {
        if (insight.getCampaignId() == null) {
            return null;
        }
        LambdaQueryWrapper<AdGroupEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AdGroupEntity::getCampaignId, insight.getCampaignId())
                .orderByAsc(AdGroupEntity::getCreatedAt)
                .last("LIMIT 1");
        return adGroupMapper.selectOne(wrapper);
    }

    private KeywordInsightEntity loadInsight(String recommendationId) {
        KeywordInsightEntity insight = insightMapper.selectById(parseUuid(recommendationId, "recommendationId"));
        if (insight == null) {
            throw new BusinessException("RECOMMENDATION_NOT_FOUND", "Keyword recommendation not found: " + recommendationId);
        }
        if (insight.getText() == null || insight.getText().isBlank()) {
            throw new BusinessException("INVALID_RECOMMENDATION", "Recommendation has no keyword text: " + recommendationId);
        }
        return insight;
    }

    private void closeInsight(KeywordInsightEntity insight, String status) {
        insight.setStatus(status);
        insight.setUpdatedAt(LocalDateTime.now());
        insightMapper.updateById(insight);
    }

    private String normalizeLibraryType(String libraryType) {
        if (libraryType == null || libraryType.isBlank()) {
            throw new BusinessException("INVALID_LIBRARY_TYPE", "Library type is required");
        }
        String lt = libraryType.trim().toLowerCase();
        return switch (lt) {
            case "harvest", "negative", "brand", "competitor" -> lt;
            default -> throw new BusinessException("INVALID_LIBRARY_TYPE", "Unsupported library type: " + libraryType);
        };
    }

    private UUID parseUuid(String value, String field) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new BusinessException("INVALID_ID", "Invalid " + field + ": " + value);
        }
    }

    private UUID parseUuidOrNull(String value) {
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
