package com.adpilot.modules.listing.service.impl;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.modules.keyword.entity.KeywordCoverageEntity;
import com.adpilot.modules.keyword.entity.KeywordInsightEntity;
import com.adpilot.modules.keyword.mapper.KeywordCoverageMapper;
import com.adpilot.modules.keyword.mapper.KeywordInsightMapper;
import com.adpilot.modules.listing.dto.ListingGenerateRequest;
import com.adpilot.modules.listing.entity.ListingContentEntity;
import com.adpilot.modules.listing.entity.ListingDraftEntity;
import com.adpilot.modules.listing.mapper.ListingContentMapper;
import com.adpilot.modules.listing.mapper.ListingDraftMapper;
import com.adpilot.modules.listing.service.ListingService;
import com.adpilot.modules.listing.vo.*;
import com.adpilot.modules.ai.service.AiAssistService;
import com.adpilot.modules.product.entity.ProductEntity;
import com.adpilot.modules.product.mapper.ProductMapper;
import com.adpilot.modules.store.entity.StoreEntity;
import com.adpilot.modules.store.mapper.StoreMapper;
import com.adpilot.modules.store.service.StoreService;
import com.adpilot.modules.store.vo.StoreVo;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ListingServiceImpl implements ListingService {

    private final ListingContentMapper listingContentMapper;
    private final ListingDraftMapper listingDraftMapper;
    private final ProductMapper productMapper;
    private final StoreMapper storeMapper;
    private final KeywordInsightMapper keywordInsightMapper;
    private final KeywordCoverageMapper keywordCoverageMapper;
    private final ObjectMapper objectMapper;
    private final AiAssistService aiAssistService;
    private final StoreService storeService;

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    // Amazon "Product name optimization" update, effective 2026-07-27: non-media
    // titles must be <= 75 characters (incl. spaces). Media titles keep the
    // legacy 200-char allowance.
    private static final int MAX_TITLE_LENGTH = 75;
    private static final int MAX_TITLE_LENGTH_MEDIA = 200;
    // New Product Highlights module: up to 125 characters of comma-separated
    // attribute phrases, surfaced under the title and fully searchable.
    private static final int MAX_HIGHLIGHTS_LENGTH = 125;
    private static final int MAX_BULLET_POINTS = 5;
    private static final int MAX_BACKEND_BYTES = 250;

    // --- Platform families the listing generator understands -----------------
    // Server-side resolution normalizes a product's store platform to one of
    // these. amazon keeps the existing marketplace rules; independent_site
    // (Shopify/WooCommerce) gets SEO-oriented content; tiktok gets a short
    // hook + hashtags. Anything unknown falls back to amazon for backward
    // compatibility.
    static final String PLATFORM_AMAZON = "amazon";
    static final String PLATFORM_INDEPENDENT = "independent_site";
    static final String PLATFORM_TIKTOK = "tiktok";

    // Independent-site SEO conventions: an SEO <title> renders best around 60
    // characters before search engines truncate it, and a meta description
    // wants roughly 155-160 characters.
    private static final int SEO_TITLE_MAX = 60;
    private static final int META_DESCRIPTION_MAX = 160;
    private static final int META_DESCRIPTION_MIN = 150;
    // TikTok-style titles stay short and punchy.
    private static final int TIKTOK_TITLE_MAX = 50;
    private static final int MAX_SEO_KEYWORDS = 12;
    private static final int MAX_HASHTAGS = 8;

    private static final List<String> PROHIBITED_WORDS = List.of(
            "cure", "treat", "best", "#1", "guaranteed", "guarantee",
            "miracle", "magic", "instant", "permanent", "clinically proven",
            "fda approved", "doctor recommended"
    );
    // Media categories remain exempt from the 75-char title rule.
    private static final List<String> MEDIA_CATEGORY_HINTS = List.of(
            "book", "books", "music", "cd", "vinyl", "dvd", "blu-ray", "blu ray",
            "video", "movie", "movies", "magazine", "media", "software", "video game", "game"
    );

    /** True when the product belongs to a media category exempt from the 75-char title rule. */
    private boolean isMediaProduct(ProductEntity product) {
        if (product == null || product.getCategory() == null) {
            return false;
        }
        String cat = product.getCategory().toLowerCase();
        return MEDIA_CATEGORY_HINTS.stream().anyMatch(cat::contains);
    }

    /** Effective max title length for a product (media keeps the legacy 200). */
    private int maxTitleLength(ProductEntity product) {
        return isMediaProduct(product) ? MAX_TITLE_LENGTH_MEDIA : MAX_TITLE_LENGTH;
    }

    /**
     * Builds a Product Highlights string: up to {@value #MAX_HIGHLIGHTS_LENGTH}
     * characters of comma-separated short attribute phrases (Entity SEO). Pulls
     * from structured attributes first (material, use case, audience, category),
     * then fills with high-confidence keywords. Truncates on a phrase boundary.
     */
    private String buildProductHighlights(String category, String targetAudience,
                                          List<String> sellingPoints, List<String> keywords) {
        LinkedHashSet<String> phrases = new LinkedHashSet<>();
        if (sellingPoints != null) {
            sellingPoints.stream().filter(Objects::nonNull).map(String::trim)
                    .filter(s -> !s.isEmpty()).forEach(phrases::add);
        }
        if (category != null && !category.isBlank()) {
            phrases.add(category.trim());
        }
        if (targetAudience != null && !targetAudience.isBlank()
                && !"consumers".equalsIgnoreCase(targetAudience)) {
            phrases.add("For " + targetAudience.trim());
        }
        if (keywords != null) {
            keywords.stream().filter(Objects::nonNull).map(String::trim)
                    .filter(s -> !s.isEmpty()).limit(10).forEach(phrases::add);
        }

        StringBuilder sb = new StringBuilder();
        for (String phrase : phrases) {
            String next = sb.length() == 0 ? phrase : ", " + phrase;
            if (sb.length() + next.length() > MAX_HIGHLIGHTS_LENGTH) {
                break;
            }
            sb.append(next);
        }
        return sb.toString();
    }

    /** Clamp a title to its effective max length on a word boundary when possible. */
    private String clampTitle(String title, int max) {
        if (title == null || title.length() <= max) {
            return title;
        }
        String cut = title.substring(0, max);
        int lastSpace = cut.lastIndexOf(' ');
        return lastSpace > max / 2 ? cut.substring(0, lastSpace).trim() : cut.trim();
    }

    /**
     * Resolve the platform family that drives generation rules for a product.
     * An explicit override on the request wins; otherwise we resolve it
     * server-side from the product's store (product -> storeId -> store
     * platform) so the frontend does not have to pass anything. Returns one of
     * {@link #PLATFORM_AMAZON}, {@link #PLATFORM_INDEPENDENT} or
     * {@link #PLATFORM_TIKTOK}, defaulting to amazon for unknown/unresolvable
     * stores to preserve the original behavior.
     */
    String resolvePlatformFamily(ProductEntity product, ListingGenerateRequest request) {
        String override = request != null ? normalizePlatformFamily(request.getPlatform()) : null;
        if (override != null) {
            return override;
        }
        try {
            if (product != null && product.getStoreId() != null) {
                StoreVo store = storeService.getStoreById(product.getStoreId().toString());
                if (store != null) {
                    String fromPlatform = normalizePlatformFamily(store.getPlatform());
                    if (fromPlatform != null) {
                        return fromPlatform;
                    }
                    String fromGroup = normalizePlatformFamily(store.getPlatformGroup());
                    if (fromGroup != null) {
                        return fromGroup;
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to resolve platform family for product {}: {}",
                    product != null ? product.getId() : null, e.getMessage());
        }
        return PLATFORM_AMAZON;
    }

    /**
     * Normalize a platform/channel string to a generation family
     * (amazon / independent_site / tiktok), or null when it carries no signal.
     */
    private String normalizePlatformFamily(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String p = raw.trim().toLowerCase();
        if (p.startsWith("amazon") || p.equals("amz") || p.equals("marketplace")) {
            return PLATFORM_AMAZON;
        }
        if (p.startsWith("shopify") || p.startsWith("woo") || p.startsWith("wordpress")
                || p.startsWith("wp") || p.equals("independent_site") || p.equals("independent")
                || p.equals("independent-site")) {
            return PLATFORM_INDEPENDENT;
        }
        if (p.startsWith("tiktok") || p.equals("tk")) {
            return PLATFORM_TIKTOK;
        }
        return null;
    }

    /** Clamp text to a max length on a word boundary when possible (generic). */
    private String clampToWordBoundary(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        String cut = text.substring(0, max);
        int lastSpace = cut.lastIndexOf(' ');
        return lastSpace > max / 2 ? cut.substring(0, lastSpace).trim() : cut.trim();
    }

    // The structured attribute keys that matter most for Entity SEO / COSMO
    // intent matching, in display order. Surfaced in the UI and scored on
    // completeness so sellers fill in the data Rufus/Alexa+ rely on.
    private static final List<String> ENTITY_ATTRIBUTE_KEYS = List.of(
            "Material", "Color", "Size", "Compatibility", "UseCase",
            "TargetAudience", "SpecialFeatures"
    );

    // Promotional phrases that Amazon disallows in titles / names.
    private static final List<String> PROMOTIONAL_PHRASES = List.of(
            "free shipping", "sale", "discount", "% off", "percent off", "best seller",
            "bestseller", "hot sale", "limited time", "buy now", "lowest price",
            "cheap", "deal", "free gift", "new arrival", "top rated"
    );

    /**
     * Builds the structured attribute map (Entity SEO). Seeds from the product
     * and request, leaving unknown keys absent so completeness can be scored.
     */
    private Map<String, String> buildAttributes(ProductEntity product, String category,
                                                String targetAudience, List<String> sellingPoints,
                                                Map<String, String> provided) {
        Map<String, String> attrs = new LinkedHashMap<>();
        // Caller-provided values win.
        if (provided != null) {
            provided.forEach((k, v) -> {
                if (k != null && v != null && !v.isBlank()) {
                    attrs.put(k, v.trim());
                }
            });
        }
        if (!attrs.containsKey("TargetAudience") && targetAudience != null
                && !targetAudience.isBlank() && !"consumers".equalsIgnoreCase(targetAudience)) {
            attrs.put("TargetAudience", targetAudience.trim());
        }
        if (!attrs.containsKey("UseCase") && category != null && !category.isBlank()) {
            attrs.put("UseCase", category.trim());
        }
        if (!attrs.containsKey("SpecialFeatures") && sellingPoints != null && !sellingPoints.isEmpty()) {
            attrs.put("SpecialFeatures", sellingPoints.stream()
                    .filter(Objects::nonNull).limit(3).collect(Collectors.joining(", ")));
        }
        return attrs;
    }

    /** Parse the stored attributes JSON into an ordered map (empty on error). */
    private Map<String, String> parseAttributes(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<LinkedHashMap<String, String>>() {});
        } catch (JsonProcessingException e) {
            return new LinkedHashMap<>();
        }
    }

    /** First promotional phrase found in the text, or null. */
    private String findPromotionalPhrase(String text) {
        if (text == null) {
            return null;
        }
        String lower = text.toLowerCase();
        return PROMOTIONAL_PHRASES.stream().filter(lower::contains).findFirst().orElse(null);
    }

    /** True when the text stuffs special characters (e.g. !!!, ***, ~~~). */
    private boolean hasSpecialCharStuffing(String text) {
        if (text == null) {
            return false;
        }
        // 3+ of the same decorative symbol in a row, or many symbols overall.
        if (text.matches(".*([!*~#@^]|\\$){3,}.*")) {
            return true;
        }
        long symbols = text.chars().filter(c -> "!*~#@^$%&".indexOf(c) >= 0).count();
        return symbols > 5;
    }

    @Override
    public ListingContentVo getListingContent(String productId) {
        UUID productUuid = UUID.fromString(productId);
        requireAccessibleProduct(productUuid);
        LambdaQueryWrapper<ListingContentEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ListingContentEntity::getProductId, productUuid)
               .orderByDesc(ListingContentEntity::getUpdatedAt)
               .last("LIMIT 1");
        ListingContentEntity entity = listingContentMapper.selectOne(wrapper);
        if (entity == null) {
            throw new BusinessException("LISTING_NOT_FOUND", "No listing content found for product: " + productId);
        }
        return toVo(entity);
    }

    @Override
    public java.util.List<ListingContentVo> listVersions(String productId) {
        UUID productUuid = UUID.fromString(productId);
        requireAccessibleProduct(productUuid);
        LambdaQueryWrapper<ListingContentEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ListingContentEntity::getProductId, productUuid)
               .orderByDesc(ListingContentEntity::getUpdatedAt);
        List<ListingContentEntity> all = listingContentMapper.selectList(wrapper);
        List<ListingContentVo> result = new ArrayList<>();
        for (ListingContentEntity e : all) {
            result.add(toVo(e));
        }
        return result;
    }

    @Override
    @Transactional
    public ListingContentVo generateDraft(String productId, ListingGenerateRequest request, String userId) {
        UUID productUuid = UUID.fromString(productId);
        ProductEntity product = requireAccessibleProduct(productUuid);

        // Resolve the platform family that drives the generation rules. Resolved
        // server-side from the product's store unless the request overrides it.
        String platformFamily = resolvePlatformFamily(product, request);

        // Gather keywords from keyword_insights for this product
        LambdaQueryWrapper<KeywordInsightEntity> kwWrapper = new LambdaQueryWrapper<>();
        kwWrapper.eq(KeywordInsightEntity::getProductId, productUuid)
                 .in(KeywordInsightEntity::getSegment, "winner", "category", "long_tail")
                 .orderByDesc(KeywordInsightEntity::getConfidenceScore)
                 .last("LIMIT 30");
        List<KeywordInsightEntity> insights = keywordInsightMapper.selectList(kwWrapper);
        List<String> keywords = insights.stream()
                .map(KeywordInsightEntity::getText)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());

        String brand = product.getBrand() != null ? product.getBrand() : "Brand";
        String productName = product.getName() != null ? product.getName() : "Product";
        String category = product.getCategory() != null ? product.getCategory() : "";
        List<String> sellingPoints = request.getSellingPoints() != null ? request.getSellingPoints() : List.of();
        String targetAudience = request.getTargetAudience() != null ? request.getTargetAudience() : "consumers";

        ListingContentEntity entity = new ListingContentEntity();
        entity.setProductId(productUuid);
        entity.setMarketplaceId(resolveMarketplaceId(request.getMarketplaceId(), product));

        if (PLATFORM_AMAZON.equals(platformFamily)) {
            populateAmazonListing(entity, userId, product, brand, productName, category,
                    sellingPoints, targetAudience, keywords, request);
        } else {
            // independent_site (Shopify/WooCommerce) and tiktok both use the
            // SEO-oriented shape; tiktok tweaks the title + uses hashtags.
            populateSeoListing(entity, platformFamily, userId, product, brand, productName,
                    category, sellingPoints, targetAudience, keywords);
        }

        entity.setSubjectMatter(category);
        entity.setIntendedUse(targetAudience);
        entity.setTargetAudience(targetAudience);
        entity.setStatus("draft");
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());

        // Calculate scores (platform-aware)
        ListingScoreVo scores = calculateScores(entity, product, keywords, platformFamily);
        entity.setListingScore(scores.getListingScore());
        entity.setComplianceScore(scores.getComplianceScore());
        entity.setSeoScore(scores.getSeoScore());
        entity.setConversionScore(scores.getConversionScore());

        listingContentMapper.insert(entity);
        log.info("Listing generated for product {} (platform={}): title={}",
                productId, platformFamily, entity.getTitle());
        return toVo(entity);
    }

    /**
     * Populate the entity with Amazon-shaped content: media-aware 75/200-char
     * title clamp, Product Highlights (Entity SEO), 5 bullet points, backend
     * search terms clamped to {@value #MAX_BACKEND_BYTES} bytes. This preserves
     * the original behavior exactly for Amazon callers.
     */
    private void populateAmazonListing(ListingContentEntity entity, String userId, ProductEntity product,
                                       String brand, String productName, String category,
                                       List<String> sellingPoints, String targetAudience,
                                       List<String> keywords, ListingGenerateRequest request) {
        // --- Try AI generation first; fall back to templates if unavailable ---
        AiListing ai = tryGenerateWithAi(userId, product, brand, productName, category,
                sellingPoints, targetAudience, keywords);

        String title;
        List<String> bulletPoints;
        String description;
        String backendSearchTerms;
        String productHighlights;

        if (ai != null) {
            title = ai.title;
            bulletPoints = ai.bulletPoints;
            description = ai.description;
            backendSearchTerms = ai.backendSearchTerms;
            productHighlights = (ai.productHighlights != null && !ai.productHighlights.isBlank())
                    ? ai.productHighlights
                    : buildProductHighlights(category, targetAudience, sellingPoints, keywords);
        } else {
            // Title (Entity SEO): [Brand] [Product Name] + at most one key
            // differentiator. Kept short so it fits the 75-char rule; remaining
            // attributes move into Product Highlights instead of being stuffed
            // into the title.
            String primaryFeature = sellingPoints.isEmpty() ? "" : sellingPoints.get(0);
            title = primaryFeature.isEmpty()
                    ? String.format("%s %s", brand, productName)
                    : String.format("%s %s %s", brand, productName, primaryFeature);

            // Product Highlights carry the structured attribute phrases.
            productHighlights = buildProductHighlights(category, targetAudience, sellingPoints, keywords);

            bulletPoints = new ArrayList<>();
            bulletPoints.add(String.format("PREMIUM QUALITY: %s %s is designed with top-grade materials for lasting durability and superior performance.",
                    brand, productName));
            bulletPoints.add(String.format("KEY FEATURES: %s",
                    sellingPoints.isEmpty() ? "Thoughtfully designed for everyday use." : String.join("; ", sellingPoints)));
            bulletPoints.add(String.format("PERFECT FOR %s: Ideal for %s who demand quality and reliability in their daily routine.",
                    targetAudience.toUpperCase(), targetAudience));
            if (!keywords.isEmpty()) {
                bulletPoints.add(String.format("VERSATILE USE: Great for %s. %s",
                        keywords.subList(0, Math.min(5, keywords.size())).stream().collect(Collectors.joining(", ")),
                        category.isEmpty() ? "" : "Perfect addition to your " + category + " collection."));
            } else {
                bulletPoints.add(String.format("VERSATILE USE: A must-have %s product that adapts to your lifestyle.", category.isEmpty() ? "" : category));
            }
            bulletPoints.add(String.format("SATISFACTION GUARANTEED: %s stands behind every product. Contact us for any questions or support.",
                    brand));

            description = String.format(
                    "Introducing the %s %s - the ultimate solution for %s. " +
                    "Crafted with precision and designed for performance, this %s delivers exceptional value. " +
                    "%s %s",
                    brand, productName, targetAudience, productName,
                    productHighlights.isEmpty() ? "" : "Featuring " + productHighlights + ". ",
                    "Order now and experience the difference that quality makes."
            );

            List<String> backendTerms = new ArrayList<>(keywords);
            if (!category.isEmpty()) {
                backendTerms.add(category.toLowerCase());
            }
            backendSearchTerms = backendTerms.stream()
                    .distinct()
                    .collect(Collectors.joining(" "));
        }

        // Apply Amazon rules: media-aware title clamp + highlights clamp.
        title = clampTitle(title, maxTitleLength(product));
        if (productHighlights != null && productHighlights.length() > MAX_HIGHLIGHTS_LENGTH) {
            productHighlights = productHighlights.substring(0, MAX_HIGHLIGHTS_LENGTH);
        }
        if (backendSearchTerms != null && backendSearchTerms.getBytes().length > MAX_BACKEND_BYTES) {
            byte[] bytes = backendSearchTerms.getBytes();
            backendSearchTerms = new String(bytes, 0, Math.min(bytes.length, MAX_BACKEND_BYTES)).trim();
            int lastSpace = backendSearchTerms.lastIndexOf(' ');
            if (lastSpace > 0) {
                backendSearchTerms = backendSearchTerms.substring(0, lastSpace);
            }
        }

        entity.setTitle(title);
        entity.setBulletPoints(toJson(bulletPoints));
        entity.setDescription(description);
        entity.setBackendSearchTerms(backendSearchTerms);
        entity.setProductHighlights(productHighlights);
        Map<String, String> attributes = buildAttributes(product, category, targetAudience,
                sellingPoints, ai != null ? ai.attributes : request.getAttributes());
        entity.setAttributes(toJson(attributes));
    }

    /**
     * Populate the entity with SEO-oriented content for independent sites
     * (Shopify/WooCommerce) and TikTok. Produces an SEO title (~{@value #SEO_TITLE_MAX}
     * chars), a meta description (~150-160 chars, stored in the
     * {@code product_highlights} column), an HTML-friendly product description
     * body, and SEO keywords / hashtags (stored in {@code backend_search_terms}
     * WITHOUT the Amazon byte cap). No Amazon backend-byte or 75-char rules are
     * applied.
     */
    private void populateSeoListing(ListingContentEntity entity, String platformFamily, String userId,
                                    ProductEntity product, String brand, String productName,
                                    String category, List<String> sellingPoints, String targetAudience,
                                    List<String> keywords) {
        boolean tiktok = PLATFORM_TIKTOK.equals(platformFamily);

        AiSeoListing ai = tryGenerateSeoWithAi(platformFamily, userId, product, brand, productName,
                category, sellingPoints, targetAudience, keywords);

        String seoTitle;
        String metaDescription;
        String description;
        String seoKeywords;
        List<String> bulletPoints;

        if (ai != null) {
            seoTitle = ai.title;
            metaDescription = ai.metaDescription;
            description = ai.description;
            seoKeywords = ai.seoKeywords;
            bulletPoints = ai.bulletPoints != null ? ai.bulletPoints
                    : buildSeoBullets(brand, productName, category, sellingPoints, keywords);
        } else {
            seoTitle = buildSeoTitle(tiktok, brand, productName, category, sellingPoints);
            metaDescription = buildMetaDescription(brand, productName, category, targetAudience,
                    sellingPoints, keywords);
            bulletPoints = buildSeoBullets(brand, productName, category, sellingPoints, keywords);
            description = buildHtmlDescription(brand, productName, category, targetAudience,
                    sellingPoints, bulletPoints);
            seoKeywords = tiktok
                    ? buildHashtags(productName, category, keywords)
                    : buildSeoKeywords(category, keywords);
        }

        // SEO conventions (no Amazon byte rules): clamp title to its channel max
        // and the meta description to ~160 chars on a word boundary.
        seoTitle = clampToWordBoundary(seoTitle, tiktok ? TIKTOK_TITLE_MAX : SEO_TITLE_MAX);
        metaDescription = clampToWordBoundary(metaDescription, META_DESCRIPTION_MAX);

        entity.setTitle(seoTitle);
        // product_highlights column carries the meta description for SEO channels.
        entity.setProductHighlights(metaDescription);
        entity.setDescription(description);
        entity.setBulletPoints(toJson(bulletPoints));
        // backend_search_terms column carries SEO keywords / hashtags, NOT
        // Amazon backend search terms — so no 250-byte clamp here.
        entity.setBackendSearchTerms(seoKeywords);
        Map<String, String> attributes = buildAttributes(product, category, targetAudience,
                sellingPoints, ai != null ? ai.attributes : null);
        entity.setAttributes(toJson(attributes));
    }

    /** SEO <title>: brand + product + one differentiator, kept concise. */
    private String buildSeoTitle(boolean tiktok, String brand, String productName,
                                 String category, List<String> sellingPoints) {
        String primary = sellingPoints.isEmpty() ? "" : sellingPoints.get(0);
        if (tiktok) {
            // Short, punchy hook for social commerce.
            return primary.isEmpty()
                    ? productName
                    : productName + " | " + primary;
        }
        StringBuilder sb = new StringBuilder();
        if (brand != null && !brand.isBlank() && !"Brand".equals(brand)) {
            sb.append(brand).append(' ');
        }
        sb.append(productName);
        if (!primary.isEmpty()) {
            sb.append(" - ").append(primary);
        } else if (category != null && !category.isBlank()) {
            sb.append(" - ").append(category);
        }
        return sb.toString();
    }

    /** Meta description aimed at ~150-160 characters for search snippets. */
    private String buildMetaDescription(String brand, String productName, String category,
                                        String targetAudience, List<String> sellingPoints,
                                        List<String> keywords) {
        StringBuilder sb = new StringBuilder();
        sb.append("Shop the ");
        if (brand != null && !brand.isBlank() && !"Brand".equals(brand)) {
            sb.append(brand).append(' ');
        }
        sb.append(productName);
        if (category != null && !category.isBlank()) {
            sb.append(" — premium ").append(category.toLowerCase());
        }
        sb.append('.');
        if (!sellingPoints.isEmpty()) {
            sb.append(' ').append(sellingPoints.stream().limit(2)
                    .collect(Collectors.joining(", "))).append('.');
        } else if (!keywords.isEmpty()) {
            sb.append(" Great for ").append(keywords.stream().limit(3)
                    .collect(Collectors.joining(", "))).append('.');
        }
        if (targetAudience != null && !targetAudience.isBlank()
                && !"consumers".equalsIgnoreCase(targetAudience)) {
            sb.append(" Perfect for ").append(targetAudience).append('.');
        }
        sb.append(" Free shipping & easy returns.");
        // Pad toward the recommended minimum without exceeding the cap.
        if (sb.length() < META_DESCRIPTION_MIN) {
            sb.append(" Order yours today and discover the difference.");
        }
        return sb.toString();
    }

    /** Short feature bullets reused as an HTML feature list for SEO bodies. */
    private List<String> buildSeoBullets(String brand, String productName, String category,
                                         List<String> sellingPoints, List<String> keywords) {
        List<String> bullets = new ArrayList<>();
        if (!sellingPoints.isEmpty()) {
            sellingPoints.stream().filter(Objects::nonNull).map(String::trim)
                    .filter(s -> !s.isEmpty()).limit(MAX_BULLET_POINTS).forEach(bullets::add);
        }
        if (bullets.isEmpty()) {
            bullets.add("Quality " + (category.isBlank() ? "product" : category)
                    + " from " + (brand == null || brand.isBlank() ? "our brand" : brand));
            if (!keywords.isEmpty()) {
                bullets.add("Ideal for " + keywords.stream().limit(3)
                        .collect(Collectors.joining(", ")));
            }
            bullets.add("Designed for everyday use and lasting value");
        }
        return bullets;
    }

    /** HTML-friendly product description body for independent storefronts. */
    private String buildHtmlDescription(String brand, String productName, String category,
                                        String targetAudience, List<String> sellingPoints,
                                        List<String> bulletPoints) {
        StringBuilder sb = new StringBuilder();
        sb.append("<p>Meet the <strong>");
        if (brand != null && !brand.isBlank() && !"Brand".equals(brand)) {
            sb.append(brand).append(' ');
        }
        sb.append(productName).append("</strong>");
        if (category != null && !category.isBlank()) {
            sb.append(", a ").append(category.toLowerCase()).append(" built to perform");
        }
        sb.append('.');
        if (targetAudience != null && !targetAudience.isBlank()
                && !"consumers".equalsIgnoreCase(targetAudience)) {
            sb.append(" Thoughtfully crafted for ").append(targetAudience).append('.');
        }
        sb.append("</p>");
        if (bulletPoints != null && !bulletPoints.isEmpty()) {
            sb.append("<ul>");
            for (String b : bulletPoints) {
                sb.append("<li>").append(b).append("</li>");
            }
            sb.append("</ul>");
        }
        sb.append("<p>Add it to your cart today and enjoy fast shipping with hassle-free returns.</p>");
        return sb.toString();
    }

    /** Comma-separated SEO keywords (no byte cap), deduplicated. */
    private String buildSeoKeywords(String category, List<String> keywords) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        keywords.stream().filter(Objects::nonNull).map(String::trim)
                .filter(s -> !s.isEmpty()).limit(MAX_SEO_KEYWORDS).forEach(set::add);
        if (category != null && !category.isBlank()) {
            set.add(category.toLowerCase());
        }
        return String.join(", ", set);
    }

    /** Space-separated hashtags for TikTok-style channels. */
    private String buildHashtags(String productName, String category, List<String> keywords) {
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        if (productName != null) {
            tags.add(toHashtag(productName));
        }
        if (category != null && !category.isBlank()) {
            tags.add(toHashtag(category));
        }
        keywords.stream().filter(Objects::nonNull).limit(MAX_HASHTAGS)
                .map(this::toHashtag).filter(t -> t.length() > 1).forEach(tags::add);
        return tags.stream().filter(t -> t.length() > 1).limit(MAX_HASHTAGS)
                .collect(Collectors.joining(" "));
    }

    private String toHashtag(String text) {
        if (text == null) {
            return "#";
        }
        String cleaned = text.toLowerCase().replaceAll("[^a-z0-9]+", "");
        return "#" + cleaned;
    }


    /**
     * Attempt to generate the listing via the configured AI provider. Returns
     * null when AI is disabled or the response can't be parsed, so the caller
     * falls back to template generation.
     */
    private AiListing tryGenerateWithAi(String userId, ProductEntity product, String brand,
                                        String productName, String category,
                                        List<String> sellingPoints, String targetAudience,
                                        List<String> keywords) {
        if (!aiAssistService.isEnabled()) {
            return null;
        }
        String system = "You are an expert Amazon listing copywriter following Amazon's 2026 "
                + "product-name optimization rules and Entity SEO / COSMO best practices. "
                + "Return ONLY valid minified JSON with keys: title (string, <=75 chars, brand + "
                + "core product term + at most one key differentiator, NO keyword stuffing, NO "
                + "promotional language, do not repeat any word more than twice), "
                + "productHighlights (string, <=125 chars, comma-separated SHORT attribute phrases "
                + "covering material, key features, use case, target audience and key specs - "
                + "phrases not full sentences), "
                + "attributes (object with string values for keys Material, Color, Size, "
                + "Compatibility, UseCase, TargetAudience, SpecialFeatures - omit keys you cannot "
                + "infer), "
                + "bulletPoints (array of exactly 5 strings), description (string), "
                + "backendSearchTerms (string, space-separated, no commas). "
                + "Do not include prohibited/absolute claims (e.g. best, #1, guaranteed, cure). "
                + "No markdown, no code fences, JSON only.";
        String user = "Product: " + productName + "\n"
                + "Brand: " + brand + "\n"
                + "Category: " + category + "\n"
                + "Target audience: " + targetAudience + "\n"
                + "Selling points: " + String.join("; ", sellingPoints) + "\n"
                + "Keywords to weave in: " + keywords.stream().limit(20).collect(Collectors.joining(", "));

        Optional<String> out;
        try {
            out = aiAssistService.generate("listing_generation",
                    userId, product.getStoreId() != null ? product.getStoreId().toString() : null,
                    system, user);
        } catch (Exception e) {
            // Any AI provider / network / config error must not fail the whole
            // generation — fall back to the deterministic template instead.
            log.warn("AI listing generation call failed, falling back to template: {}", e.getMessage());
            return null;
        }
        if (out.isEmpty()) {
            return null;
        }
        try {
            String json = extractJson(out.get());
            JsonNode root = objectMapper.readTree(json);
            AiListing result = new AiListing();
            result.title = root.path("title").asText(null);
            List<String> bullets = new ArrayList<>();
            JsonNode bp = root.path("bulletPoints");
            if (bp.isArray()) {
                bp.forEach(n -> bullets.add(n.asText()));
            }
            result.bulletPoints = bullets.isEmpty() ? null : bullets;
            result.description = root.path("description").asText(null);
            result.backendSearchTerms = root.path("backendSearchTerms").asText(null);
            result.productHighlights = root.path("productHighlights").asText(null);
            JsonNode attrNode = root.path("attributes");
            if (attrNode.isObject()) {
                Map<String, String> attrs = new LinkedHashMap<>();
                attrNode.fields().forEachRemaining(e -> {
                    if (e.getValue() != null && !e.getValue().isNull()) {
                        attrs.put(e.getKey(), e.getValue().asText());
                    }
                });
                result.attributes = attrs.isEmpty() ? null : attrs;
            }
            // Require the essential fields; otherwise fall back.
            if (result.title == null || result.bulletPoints == null || result.description == null) {
                return null;
            }
            return result;
        } catch (Exception e) {
            log.warn("Failed to parse AI listing JSON, falling back to template: {}", e.getMessage());
            return null;
        }
    }

    /** Strip optional code fences/prose around a JSON object. */
    private String extractJson(String raw) {
        String s = raw.trim();
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return s.substring(start, end + 1);
        }
        return s;
    }

    /** Holder for AI-generated listing fields. */
    private static class AiListing {
        String title;
        List<String> bulletPoints;
        String description;
        String backendSearchTerms;
        String productHighlights;
        Map<String, String> attributes;
    }

    /** Holder for AI-generated SEO (independent-site / tiktok) listing fields. */
    private static class AiSeoListing {
        String title;
        String metaDescription;
        String description;
        String seoKeywords;
        List<String> bulletPoints;
        Map<String, String> attributes;
    }

    /**
     * Attempt to generate an SEO-oriented listing (independent site / tiktok)
     * via the configured AI provider. Returns null when AI is disabled or the
     * response can't be parsed, so the caller falls back to template generation.
     */
    private AiSeoListing tryGenerateSeoWithAi(String platformFamily, String userId, ProductEntity product,
                                              String brand, String productName, String category,
                                              List<String> sellingPoints, String targetAudience,
                                              List<String> keywords) {
        if (!aiAssistService.isEnabled()) {
            return null;
        }
        boolean tiktok = PLATFORM_TIKTOK.equals(platformFamily);
        String channel = tiktok ? "TikTok Shop social-commerce" : "Shopify/WooCommerce independent-store";
        String keywordsField = tiktok
                ? "seoKeywords (string, space-separated hashtags starting with #, no commas)"
                : "seoKeywords (string, comma-separated SEO keywords)";
        String titleRule = tiktok
                ? "title (string, <=" + TIKTOK_TITLE_MAX + " chars, short punchy hook)"
                : "title (string, <=" + SEO_TITLE_MAX + " chars, SEO page title)";
        String system = "You are an expert e-commerce SEO copywriter for a " + channel + " storefront. "
                + "Return ONLY valid minified JSON with keys: "
                + titleRule + ", "
                + "metaDescription (string, " + META_DESCRIPTION_MIN + "-" + META_DESCRIPTION_MAX
                + " chars, compelling search snippet), "
                + "description (string, HTML-friendly body using <p>/<ul>/<li> tags), "
                + "bulletPoints (array of 3-5 short feature strings), "
                + keywordsField + ", "
                + "attributes (object with string values for keys Material, Color, Size, "
                + "UseCase, TargetAudience - omit keys you cannot infer). "
                + "Do NOT apply Amazon backend-search-term byte limits. "
                + "Do not include absolute/unverified claims (e.g. best, #1, guaranteed, cure). "
                + "No markdown, no code fences, JSON only.";
        String user = "Product: " + productName + "\n"
                + "Brand: " + brand + "\n"
                + "Category: " + category + "\n"
                + "Target audience: " + targetAudience + "\n"
                + "Selling points: " + String.join("; ", sellingPoints) + "\n"
                + "Keywords to weave in: " + keywords.stream().limit(20).collect(Collectors.joining(", "));

        Optional<String> out;
        try {
            out = aiAssistService.generate("listing_generation_seo",
                    userId, product.getStoreId() != null ? product.getStoreId().toString() : null,
                    system, user);
        } catch (Exception e) {
            log.warn("AI SEO listing generation call failed, falling back to template: {}", e.getMessage());
            return null;
        }
        if (out.isEmpty()) {
            return null;
        }
        try {
            String json = extractJson(out.get());
            JsonNode root = objectMapper.readTree(json);
            AiSeoListing result = new AiSeoListing();
            result.title = root.path("title").asText(null);
            result.metaDescription = root.path("metaDescription").asText(null);
            result.description = root.path("description").asText(null);
            result.seoKeywords = root.path("seoKeywords").asText(null);
            List<String> bullets = new ArrayList<>();
            JsonNode bp = root.path("bulletPoints");
            if (bp.isArray()) {
                bp.forEach(n -> bullets.add(n.asText()));
            }
            result.bulletPoints = bullets.isEmpty() ? null : bullets;
            JsonNode attrNode = root.path("attributes");
            if (attrNode.isObject()) {
                Map<String, String> attrs = new LinkedHashMap<>();
                attrNode.fields().forEachRemaining(e -> {
                    if (e.getValue() != null && !e.getValue().isNull()) {
                        attrs.put(e.getKey(), e.getValue().asText());
                    }
                });
                result.attributes = attrs.isEmpty() ? null : attrs;
            }
            // Require the essential SEO fields; otherwise fall back.
            if (result.title == null || result.metaDescription == null || result.description == null) {
                return null;
            }
            return result;
        } catch (Exception e) {
            log.warn("Failed to parse AI SEO listing JSON, falling back to template: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public ListingScoreVo scoreListing(String productId) {
        UUID productUuid = UUID.fromString(productId);
        ProductEntity product = requireAccessibleProduct(productUuid);
        LambdaQueryWrapper<ListingContentEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ListingContentEntity::getProductId, productUuid)
               .orderByDesc(ListingContentEntity::getUpdatedAt)
               .last("LIMIT 1");
        ListingContentEntity entity = listingContentMapper.selectOne(wrapper);

        // Platform family drives which SEO rules to apply (resolved from store).
        String platformFamily = resolvePlatformFamily(product, null);

        // No generated draft yet — score the product's own listing data instead of
        // returning 400, so the page works without first generating a draft. The
        // transient listing is NOT persisted (there is no draft row to update).
        if (entity == null) {
            ListingContentEntity transientEntity = new ListingContentEntity();
            transientEntity.setProductId(productUuid);
            transientEntity.setTitle(product != null && product.getName() != null ? product.getName() : "");
            transientEntity.setBulletPoints("[]");
            transientEntity.setDescription("");
            transientEntity.setBackendSearchTerms("");
            List<String> seedKeywords = loadScoringKeywords(productUuid);
            return calculateScores(transientEntity, product, seedKeywords, platformFamily);
        }

        List<String> keywords = loadScoringKeywords(productUuid);

        ListingScoreVo scores = calculateScores(entity, product, keywords, platformFamily);

        // Update entity with new scores
        entity.setListingScore(scores.getListingScore());
        entity.setComplianceScore(scores.getComplianceScore());
        entity.setSeoScore(scores.getSeoScore());
        entity.setConversionScore(scores.getConversionScore());
        entity.setUpdatedAt(LocalDateTime.now());
        listingContentMapper.updateById(entity);

        return scores;
    }

    /** Parse a UUID string, returning null for null/blank/invalid values (e.g.
     *  the frontend's "default" sentinel) instead of throwing. */
    private UUID parseUuidOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Resolve the marketplace id for a listing draft. Uses the request value
     * when valid; otherwise falls back to the product's store marketplace.
     * listing_contents.marketplace_id is NOT NULL, so this must never return
     * null for a product whose store has a marketplace.
     */
    private UUID resolveMarketplaceId(String requested, ProductEntity product) {
        UUID fromRequest = parseUuidOrNull(requested);
        if (fromRequest != null) {
            return fromRequest;
        }
        if (product != null && product.getStoreId() != null) {
            StoreEntity store = storeMapper.selectById(product.getStoreId());
            if (store != null && store.getMarketplaceId() != null) {
                return store.getMarketplaceId();
            }
        }
        return null;
    }

    /** Winner/category/long-tail keywords used to seed listing scoring. */
    private List<String> loadScoringKeywords(UUID productUuid) {
        LambdaQueryWrapper<KeywordInsightEntity> kwWrapper = new LambdaQueryWrapper<>();
        kwWrapper.eq(KeywordInsightEntity::getProductId, productUuid)
                 .in(KeywordInsightEntity::getSegment, "winner", "category", "long_tail")
                 .orderByDesc(KeywordInsightEntity::getConfidenceScore)
                 .last("LIMIT 30");
        List<KeywordInsightEntity> insights = keywordInsightMapper.selectList(kwWrapper);
        return insights.stream()
                .map(KeywordInsightEntity::getText)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList());
    }

    @Override
    public ComplianceCheckVo checkCompliance(String productId) {
        UUID productUuid = UUID.fromString(productId);
        ProductEntity product = requireAccessibleProduct(productUuid);
        LambdaQueryWrapper<ListingContentEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ListingContentEntity::getProductId, productUuid)
               .orderByDesc(ListingContentEntity::getUpdatedAt)
               .last("LIMIT 1");
        ListingContentEntity entity = listingContentMapper.selectOne(wrapper);
        if (entity == null) {
            throw new BusinessException("LISTING_NOT_FOUND", "No listing content found for product: " + productId);
        }

        // Platform family decides which rules apply. Amazon keeps its full rule
        // set; independent_site / tiktok skip Amazon-specific byte/length rules.
        boolean amazon = PLATFORM_AMAZON.equals(resolvePlatformFamily(product, null));

        List<ComplianceCheckVo.ComplianceIssueVo> issues = new ArrayList<>();

        // Prohibited-word scan runs for every platform family.
        List<String> bullets = parseJsonList(entity.getBulletPoints());
        addProhibitedWordIssues(entity, bullets, issues);

        if (!amazon) {
            addSeoComplianceIssues(entity, issues);
            return buildComplianceResult(issues);
        }

        addAmazonComplianceIssues(product, entity, bullets, issues);
        return buildComplianceResult(issues);
    }

    /** Prohibited-word scan across title, bullets, description, backend terms and highlights. */
    private void addProhibitedWordIssues(ListingContentEntity entity, List<String> bullets,
                                         List<ComplianceCheckVo.ComplianceIssueVo> issues) {
        // Check title for prohibited words
        checkFieldForProhibitedWords("title", entity.getTitle(), issues);

        // Check bullet points
        for (int i = 0; i < bullets.size(); i++) {
            checkFieldForProhibitedWords("bullet_points[" + i + "]", bullets.get(i), issues);
        }

        // Check description
        checkFieldForProhibitedWords("description", entity.getDescription(), issues);

        // Check backend search terms
        checkFieldForProhibitedWords("backend_search_terms", entity.getBackendSearchTerms(), issues);

        // Check product highlights
        checkFieldForProhibitedWords("product_highlights", entity.getProductHighlights(), issues);
    }

    /**
     * Independent-site / tiktok: SEO-oriented advisory checks only. No Amazon
     * 75-char title cap, 125-char highlights cap, 250-byte backend rule,
     * promotional-language or Entity-SEO attribute requirements.
     */
    private void addSeoComplianceIssues(ListingContentEntity entity,
                                        List<ComplianceCheckVo.ComplianceIssueVo> issues) {
        if (entity.getTitle() != null && entity.getTitle().length() > SEO_TITLE_MAX) {
            issues.add(ComplianceCheckVo.ComplianceIssueVo.builder()
                    .field("title").severity("low").rule("seo_title_length")
                    .message("SEO 标题超过约 " + SEO_TITLE_MAX + " 个字符（当前 "
                            + entity.getTitle().length() + "），搜索结果可能被截断")
                    .suggestion("将 SEO 标题控制在 " + SEO_TITLE_MAX + " 字符左右以获得最佳展示")
                    .build());
        }
        // product_highlights carries the meta description for SEO channels.
        String meta = entity.getProductHighlights();
        if (meta == null || meta.isBlank()) {
            issues.add(ComplianceCheckVo.ComplianceIssueVo.builder()
                    .field("product_highlights").severity("low").rule("meta_description_missing")
                    .message("尚未填写 meta description（搜索摘要）")
                    .suggestion("补充约 150-160 字符的 meta description 以提升点击率")
                    .build());
        } else if (meta.length() > META_DESCRIPTION_MAX + 20) {
            issues.add(ComplianceCheckVo.ComplianceIssueVo.builder()
                    .field("product_highlights").severity("low").rule("meta_description_length")
                    .message("meta description 偏长（当前 " + meta.length()
                            + " 字符），搜索结果可能被截断")
                    .suggestion("将 meta description 控制在约 150-160 字符")
                    .build());
        }
    }

    /** Full Amazon marketplace rule set: title, highlights, promo, special chars, attributes, bullets, backend bytes. */
    private void addAmazonComplianceIssues(ProductEntity product, ListingContentEntity entity,
                                           List<String> bullets,
                                           List<ComplianceCheckVo.ComplianceIssueVo> issues) {
        // Title length — media products keep the legacy 200-char allowance,
        // everything else must satisfy the 75-char rule (effective 2026-07-27).
        int titleMax = maxTitleLength(product);
        if (entity.getTitle() != null && entity.getTitle().length() > titleMax) {
            issues.add(ComplianceCheckVo.ComplianceIssueVo.builder()
                    .field("title").severity("high").rule("title_length")
                    .message("标题超出 " + titleMax + " 个字符（当前 " + entity.getTitle().length()
                            + "）。亚马逊自 2026-07-27 起要求非媒介类标题不超过 75 字符。")
                    .suggestion("将标题精简到 " + titleMax + " 字符内，把多余属性移到「商品亮点」模块")
                    .build());
        }

        // Repeated keyword in title (Amazon: a keyword should not appear more
        // than twice) — flag any word used 3+ times.
        if (entity.getTitle() != null) {
            String repeated = findOverusedWord(entity.getTitle());
            if (repeated != null) {
                issues.add(ComplianceCheckVo.ComplianceIssueVo.builder()
                        .field("title").severity("medium").rule("keyword_repeat")
                        .message("标题中关键词「" + repeated + "」重复出现 3 次以上")
                        .suggestion("同一关键词原则上不应重复超过两次，请删除多余重复")
                        .build());
            }
        }

        // Product Highlights checks: length cap + recommend filling it in.
        if (entity.getProductHighlights() != null
                && entity.getProductHighlights().length() > MAX_HIGHLIGHTS_LENGTH) {
            issues.add(ComplianceCheckVo.ComplianceIssueVo.builder()
                    .field("product_highlights").severity("high").rule("highlights_length")
                    .message("商品亮点超出 " + MAX_HIGHLIGHTS_LENGTH + " 个字符（当前 "
                            + entity.getProductHighlights().length() + "）")
                    .suggestion("将商品亮点精简到 " + MAX_HIGHLIGHTS_LENGTH + " 字符内")
                    .build());
        } else if (entity.getProductHighlights() == null || entity.getProductHighlights().isBlank()) {
            issues.add(ComplianceCheckVo.ComplianceIssueVo.builder()
                    .field("product_highlights").severity("medium").rule("highlights_missing")
                    .message("尚未填写「商品亮点」。该模块可被搜索索引，是 AI 搜索时代的重要字段。")
                    .suggestion("用逗号分隔的短语补充材质、功能、适用场景与目标人群")
                    .build());
        }

        // Promotional language in title (Amazon: titles must not contain promo wording).
        String promo = findPromotionalPhrase(entity.getTitle());
        if (promo != null) {
            issues.add(ComplianceCheckVo.ComplianceIssueVo.builder()
                    .field("title").severity("high").rule("promotional_language")
                    .message("标题包含促销用语「" + promo + "」")
                    .suggestion("删除促销/营销用语，标题只描述商品本身")
                    .build());
        }

        // Special-character stuffing in title.
        if (hasSpecialCharStuffing(entity.getTitle())) {
            issues.add(ComplianceCheckVo.ComplianceIssueVo.builder()
                    .field("title").severity("medium").rule("special_chars")
                    .message("标题堆砌了过多特殊字符")
                    .suggestion("移除装饰性符号（如 !!!、***、~~~），保持简洁")
                    .build());
        }

        // Entity SEO: recommend filling key structured attributes that Rufus /
        // Alexa+ rely on for intent matching.
        Map<String, String> attrs = parseAttributes(entity.getAttributes());
        List<String> missingAttrs = ENTITY_ATTRIBUTE_KEYS.stream()
                .filter(k -> {
                    String v = attrs.get(k);
                    return v == null || v.isBlank();
                })
                .collect(Collectors.toList());
        if (missingAttrs.size() >= 4) {
            issues.add(ComplianceCheckVo.ComplianceIssueVo.builder()
                    .field("attributes").severity("medium").rule("attributes_incomplete")
                    .message("结构化属性不完整，缺少：" + String.join("、", missingAttrs))
                    .suggestion("补全材质、颜色、尺寸、兼容性、使用场景、目标人群、特性等属性，"
                            + "帮助 AI 搜索（COSMO/Rufus）理解并推荐你的商品")
                    .build());
        }

        // Check bullet count
        if (bullets.size() < 3) {
            issues.add(ComplianceCheckVo.ComplianceIssueVo.builder()
                    .field("bullet_points").severity("medium").rule("bullet_count")
                    .message("Only " + bullets.size() + " bullet points. Amazon recommends at least 3.")
                    .suggestion("Add more bullet points to improve listing quality")
                    .build());
        }

        // Check backend search terms byte length
        if (entity.getBackendSearchTerms() != null &&
                entity.getBackendSearchTerms().getBytes().length > MAX_BACKEND_BYTES) {
            issues.add(ComplianceCheckVo.ComplianceIssueVo.builder()
                    .field("backend_search_terms").severity("high").rule("backend_length")
                    .message("Backend search terms exceed 250 bytes")
                    .suggestion("Reduce backend search terms to fit within 250 bytes")
                    .build());
        }
    }

    /** Derives overall risk (high &gt; medium &gt; low) and submit-ability from the collected issues. */
    private ComplianceCheckVo buildComplianceResult(List<ComplianceCheckVo.ComplianceIssueVo> issues) {
        boolean hasHigh = issues.stream().anyMatch(i -> "high".equals(i.getSeverity()));
        String overallRisk = hasHigh ? "high" :
                issues.stream().anyMatch(i -> "medium".equals(i.getSeverity())) ? "medium" : "low";

        return ComplianceCheckVo.builder()
                .issues(issues)
                .overallRisk(overallRisk)
                .canSubmit(!hasHigh)
                .build();
    }

    @Override
    @Transactional
    public ListingContentVo updateDraft(String id, ListingGenerateRequest request) {
        ListingContentEntity entity = listingContentMapper.selectById(UUID.fromString(id));
        if (entity == null) {
            throw new BusinessException("LISTING_NOT_FOUND", "Listing not found: " + id);
        }

        // Direct field edits (editing an AI/template draft before upload) take
        // precedence and are applied as-is, with platform-appropriate clamping.
        ProductEntity editProduct = requireAccessibleProduct(entity.getProductId());
        String platformFamily = resolvePlatformFamily(editProduct, request);
        boolean amazon = PLATFORM_AMAZON.equals(platformFamily);
        int titleMax = amazon ? maxTitleLength(editProduct)
                : (PLATFORM_TIKTOK.equals(platformFamily) ? TIKTOK_TITLE_MAX : SEO_TITLE_MAX);
        boolean directEdit = applyDirectFieldEdits(entity, request, amazon, titleMax);

        if (!directEdit && request.getSellingPoints() != null) {
            regenerateDraftFromSellingPoints(entity, request, editProduct, platformFamily, amazon, titleMax);
        }

        if (request.getTargetAudience() != null) {
            entity.setTargetAudience(request.getTargetAudience());
            entity.setIntendedUse(request.getTargetAudience());
        }

        // Recompute quality/compliance scores so edits are reflected.
        ProductEntity scoredProduct = productMapper.selectById(entity.getProductId());
        ListingScoreVo scores = calculateScores(entity, scoredProduct, List.of(), platformFamily);
        entity.setListingScore(scores.getListingScore());
        entity.setComplianceScore(scores.getComplianceScore());
        entity.setSeoScore(scores.getSeoScore());
        entity.setConversionScore(scores.getConversionScore());

        entity.setStatus("draft");
        entity.setUpdatedAt(LocalDateTime.now());
        listingContentMapper.updateById(entity);
        return toVo(entity);
    }

    /**
     * Applies direct field edits from the request onto the draft entity with
     * platform-appropriate clamping. Returns true if any field was edited (which
     * suppresses selling-point regeneration).
     */
    private boolean applyDirectFieldEdits(ListingContentEntity entity, ListingGenerateRequest request,
                                          boolean amazon, int titleMax) {
        boolean directEdit = false;
        if (request.getTitle() != null) {
            entity.setTitle(amazon ? clampTitle(request.getTitle(), titleMax)
                    : clampToWordBoundary(request.getTitle(), titleMax));
            directEdit = true;
        }
        if (request.getProductHighlights() != null) {
            String hl = request.getProductHighlights();
            if (amazon) {
                // Amazon Product Highlights: hard 125-char cap.
                if (hl.length() > MAX_HIGHLIGHTS_LENGTH) {
                    hl = hl.substring(0, MAX_HIGHLIGHTS_LENGTH);
                }
            } else {
                // SEO channels: product_highlights carries the meta description.
                hl = clampToWordBoundary(hl, META_DESCRIPTION_MAX);
            }
            entity.setProductHighlights(hl);
            directEdit = true;
        }
        if (request.getAttributes() != null) {
            Map<String, String> attrs = new LinkedHashMap<>();
            request.getAttributes().forEach((k, v) -> {
                if (k != null && v != null && !v.isBlank()) {
                    attrs.put(k, v.trim());
                }
            });
            entity.setAttributes(toJson(attrs));
            directEdit = true;
        }
        if (request.getBulletPoints() != null) {
            List<String> bullets = request.getBulletPoints().stream()
                    .filter(Objects::nonNull)
                    .filter(b -> !b.isBlank())
                    .limit(MAX_BULLET_POINTS)
                    .collect(Collectors.toList());
            entity.setBulletPoints(toJson(bullets));
            directEdit = true;
        }
        if (request.getDescription() != null) {
            entity.setDescription(request.getDescription());
            directEdit = true;
        }
        if (request.getBackendSearchTerms() != null) {
            String backend = request.getBackendSearchTerms();
            // Amazon backend search terms: hard 250-byte cap. SEO keywords /
            // hashtags for other channels have no byte cap.
            if (amazon && backend.getBytes().length > MAX_BACKEND_BYTES) {
                byte[] bytes = backend.getBytes();
                backend = new String(bytes, 0, Math.min(bytes.length, MAX_BACKEND_BYTES)).trim();
                int lastSpace = backend.lastIndexOf(' ');
                if (lastSpace > 0) {
                    backend = backend.substring(0, lastSpace);
                }
            }
            entity.setBackendSearchTerms(backend);
            directEdit = true;
        }
        return directEdit;
    }

    /**
     * Regenerates title + highlights/meta-description from the request's selling
     * points when no direct field edits were supplied. Platform-aware: Amazon
     * builds an Entity-SEO title + Product Highlights, SEO channels build an SEO
     * title + meta description.
     */
    private void regenerateDraftFromSellingPoints(ListingContentEntity entity, ListingGenerateRequest request,
                                                  ProductEntity editProduct, String platformFamily,
                                                  boolean amazon, int titleMax) {
        String brand = editProduct != null && editProduct.getBrand() != null ? editProduct.getBrand() : "Brand";
        String productName = editProduct != null && editProduct.getName() != null ? editProduct.getName() : "Product";
        String category = editProduct != null && editProduct.getCategory() != null ? editProduct.getCategory() : "";

        List<String> sellingPoints = request.getSellingPoints();
        if (amazon) {
            // Short Entity-SEO title + attribute phrases into Product Highlights.
            String primaryFeature = sellingPoints.isEmpty() ? "" : sellingPoints.get(0);
            String title = primaryFeature.isEmpty()
                    ? String.format("%s %s", brand, productName)
                    : String.format("%s %s %s", brand, productName, primaryFeature);
            entity.setTitle(clampTitle(title, titleMax));
            entity.setProductHighlights(
                    buildProductHighlights(category, request.getTargetAudience(), sellingPoints, List.of()));
        } else {
            // SEO channels: regenerate SEO title + meta description.
            boolean tiktok = PLATFORM_TIKTOK.equals(platformFamily);
            entity.setTitle(clampToWordBoundary(
                    buildSeoTitle(tiktok, brand, productName, category, sellingPoints), titleMax));
            entity.setProductHighlights(clampToWordBoundary(
                    buildMetaDescription(brand, productName, category,
                            request.getTargetAudience(), sellingPoints, List.of()),
                    META_DESCRIPTION_MAX));
        }
    }

    @Override
    @Transactional
    public ListingContentVo approveDraft(String id, String userId) {
        ListingContentEntity entity = listingContentMapper.selectById(UUID.fromString(id));
        if (entity == null) {
            throw new BusinessException("LISTING_NOT_FOUND", "Listing not found: " + id);
        }
        requireAccessibleProduct(entity.getProductId());

        // Run compliance check before approving
        ComplianceCheckVo compliance = checkCompliance(entity.getProductId().toString());
        if (!compliance.isCanSubmit()) {
            throw new BusinessException("COMPLIANCE_FAILED", "Listing has high-severity compliance issues. Fix them before approving.");
        }

        entity.setStatus("approved");
        entity.setUpdatedAt(LocalDateTime.now());
        listingContentMapper.updateById(entity);

        // Also save as listing draft
        ListingDraftEntity draft = new ListingDraftEntity();
        draft.setProductId(entity.getProductId());
        draft.setMarketplaceId(entity.getMarketplaceId());
        draft.setSourceType("ai_generated");
        draft.setContent(toJson(entity));
        draft.setStatus("approved");
        draft.setCreatedAt(LocalDateTime.now());
        draft.setUpdatedAt(LocalDateTime.now());
        listingDraftMapper.insert(draft);

        log.info("Listing approved: id={}", id);
        return toVo(entity);
    }

    private ListingScoreVo calculateScores(ListingContentEntity entity, ProductEntity product, List<String> keywords) {
        return calculateScores(entity, product, keywords, PLATFORM_AMAZON);
    }

    private ListingScoreVo calculateScores(ListingContentEntity entity, ProductEntity product,
                                           List<String> keywords, String platformFamily) {
        boolean amazon = PLATFORM_AMAZON.equals(platformFamily);
        int seoScore = amazon ? calculateSeoScore(entity, keywords)
                : calculateSeoScoreForSeoChannel(entity, keywords);
        int complianceScore = calculateComplianceScore(entity);
        int conversionScore = calculateConversionScore(entity, product);
        int readabilityScore = calculateReadabilityScore(entity);
        int keywordCoverageScore = calculateKeywordCoverageScore(product.getStoreId(), keywords);

        int listingScore = (int) (seoScore * 0.3 + complianceScore * 0.25 + conversionScore * 0.25 +
                readabilityScore * 0.1 + keywordCoverageScore * 0.1);

        String summary = String.format(
                "SEO: %d/100, Compliance: %d/100, Conversion: %d/100, Readability: %d/100, Keyword Coverage: %d/100. Overall: %d/100.",
                seoScore, complianceScore, conversionScore, readabilityScore, keywordCoverageScore, listingScore);

        return ListingScoreVo.builder()
                .seoScore(seoScore)
                .complianceScore(complianceScore)
                .conversionScore(conversionScore)
                .readabilityScore(readabilityScore)
                .keywordCoverageScore(keywordCoverageScore)
                .listingScore(listingScore)
                .summary(summary)
                .build();
    }

    /**
     * SEO score for independent-site / tiktok channels. Rewards a concise SEO
     * title (~60 chars), a meta description in the ~150-160 range (stored in
     * product_highlights), HTML body length, SEO keywords (no Amazon byte rule)
     * and feature bullets. Never penalizes for Amazon's 75-char title cap.
     */
    private int calculateSeoScoreForSeoChannel(ListingContentEntity entity, List<String> keywords) {
        int score = 0;

        // SEO title length (25 points) — sweet spot is up to ~60 chars.
        if (entity.getTitle() != null) {
            int len = entity.getTitle().length();
            if (len >= 15 && len <= SEO_TITLE_MAX) score += 25;
            else if (len > SEO_TITLE_MAX && len <= SEO_TITLE_MAX + 20) score += 15;
            else if (len > 0) score += 10;
        }

        // Meta description (20 points) — stored in product_highlights.
        if (entity.getProductHighlights() != null && !entity.getProductHighlights().isBlank()) {
            int len = entity.getProductHighlights().length();
            if (len >= 120 && len <= META_DESCRIPTION_MAX + 10) score += 20;
            else score += 10;
        }

        // Keywords in title (15 points).
        if (entity.getTitle() != null && !keywords.isEmpty()) {
            String titleLower = entity.getTitle().toLowerCase();
            long matchCount = keywords.stream()
                    .filter(kw -> titleLower.contains(kw.toLowerCase()))
                    .count();
            score += Math.min(15, (int) (matchCount * 5));
        }

        // SEO keywords / hashtags present (15 points) — no byte cap applied.
        if (entity.getBackendSearchTerms() != null && !entity.getBackendSearchTerms().isBlank()) {
            score += 15;
        }

        // Feature bullets (10 points).
        List<String> bullets = parseJsonList(entity.getBulletPoints());
        if (bullets.size() >= 3) score += 10;
        else if (bullets.size() >= 1) score += 5;

        // Description body length (15 points).
        if (entity.getDescription() != null) {
            int len = entity.getDescription().length();
            if (len >= 300) score += 15;
            else if (len >= 100) score += 10;
            else if (len >= 30) score += 5;
        }

        return Math.min(100, score);
    }


    private int calculateSeoScore(ListingContentEntity entity, List<String> keywords) {
        int score = 0;

        // Title length (20 points) — Entity SEO favors concise titles. The
        // 75-char ceiling (incl. spaces) is the sweet spot; over-length titles
        // score 0 because Amazon will truncate / auto-rewrite them.
        if (entity.getTitle() != null) {
            int len = entity.getTitle().length();
            if (len >= 20 && len <= MAX_TITLE_LENGTH) score += 20;
            else if (len > 0 && len < 20) score += 10;
            // len > MAX_TITLE_LENGTH -> 0 (penalized)
        }

        // Keywords in title (15 points) — lighter weight now that keyword
        // coverage shifts toward Product Highlights and backend attributes.
        if (entity.getTitle() != null && !keywords.isEmpty()) {
            String titleLower = entity.getTitle().toLowerCase();
            long matchCount = keywords.stream()
                    .filter(kw -> titleLower.contains(kw.toLowerCase()))
                    .count();
            score += Math.min(15, (int) (matchCount * 5));
        }

        // Product Highlights (20 points) — the new searchable attribute module.
        if (entity.getProductHighlights() != null && !entity.getProductHighlights().isBlank()) {
            int hlLen = entity.getProductHighlights().length();
            score += 10;
            // Reward genuine comma-separated phrase coverage within the cap.
            long phraseCount = java.util.Arrays.stream(entity.getProductHighlights().split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).count();
            if (phraseCount >= 3 && hlLen <= MAX_HIGHLIGHTS_LENGTH) score += 10;
            else if (phraseCount >= 1) score += 5;
        }

        // Bullet count (15 points)
        List<String> bullets = parseJsonList(entity.getBulletPoints());
        if (bullets.size() >= 5) score += 15;
        else if (bullets.size() >= 3) score += 10;
        else if (bullets.size() >= 1) score += 5;

        // Backend search terms present (15 points)
        if (entity.getBackendSearchTerms() != null && !entity.getBackendSearchTerms().isBlank()) {
            score += 8;
            if (entity.getBackendSearchTerms().getBytes().length <= MAX_BACKEND_BYTES) {
                score += 7;
            }
        }

        // Description length (15 points)
        if (entity.getDescription() != null) {
            int len = entity.getDescription().length();
            if (len >= 500) score += 15;
            else if (len >= 200) score += 10;
            else if (len >= 50) score += 5;
        }

        // Structured attributes (Entity SEO, up to 10 points) — rewards filling
        // the attribute keys COSMO / Rufus use for intent matching. Capped total
        // still clamps to 100.
        Map<String, String> attrs = parseAttributes(entity.getAttributes());
        long filled = ENTITY_ATTRIBUTE_KEYS.stream()
                .filter(k -> attrs.get(k) != null && !attrs.get(k).isBlank())
                .count();
        score += Math.min(10, (int) (filled * 2));

        return Math.min(100, score);
    }

    private int calculateComplianceScore(ListingContentEntity entity) {
        int score = 100;
        List<String> allText = new ArrayList<>();
        if (entity.getTitle() != null) allText.add(entity.getTitle());
        if (entity.getDescription() != null) allText.add(entity.getDescription());
        if (entity.getBackendSearchTerms() != null) allText.add(entity.getBackendSearchTerms());
        allText.addAll(parseJsonList(entity.getBulletPoints()));

        for (String text : allText) {
            String lower = text.toLowerCase();
            for (String word : PROHIBITED_WORDS) {
                if (lower.contains(word)) {
                    score -= 15;
                }
            }
        }
        return Math.max(0, score);
    }

    private int calculateConversionScore(ListingContentEntity entity, ProductEntity product) {
        int score = 0;
        List<String> bullets = parseJsonList(entity.getBulletPoints());

        // Bullet count (30 points)
        if (bullets.size() >= 5) score += 30;
        else if (bullets.size() >= 3) score += 20;
        else score += 10;

        String allText = String.join(" ", bullets) + " " + (entity.getDescription() != null ? entity.getDescription() : "");
        String lower = allText.toLowerCase();

        // Warranty mention (20 points)
        if (lower.contains("warranty") || lower.contains("guarantee") || lower.contains("satisfaction")) {
            score += 20;
        }

        // Use case mentioned (20 points)
        if (lower.contains("perfect for") || lower.contains("ideal for") || lower.contains("great for") || lower.contains("use")) {
            score += 20;
        }

        // Material quality (15 points)
        if (lower.contains("premium") || lower.contains("quality") || lower.contains("durable") || lower.contains("material")) {
            score += 15;
        }

        // Has image/product info (15 points)
        if (product != null && product.getImageUrl() != null && !product.getImageUrl().isBlank()) {
            score += 15;
        }

        return Math.min(100, score);
    }

    private int calculateReadabilityScore(ListingContentEntity entity) {
        int score = 100;
        List<String> bullets = parseJsonList(entity.getBulletPoints());

        // Check sentence length in bullets
        for (String bullet : bullets) {
            if (bullet.length() > 500) score -= 5;
            // Check for excessive caps
            long capsCount = bullet.chars().filter(Character::isUpperCase).count();
            if (capsCount > bullet.length() * 0.5 && bullet.length() > 10) score -= 5;
        }

        // Check description sentences
        if (entity.getDescription() != null) {
            String[] sentences = entity.getDescription().split("\\.");
            for (String sentence : sentences) {
                if (sentence.trim().length() > 300) score -= 5;
            }
        }

        return Math.max(0, Math.min(100, score));
    }

    private int calculateKeywordCoverageScore(UUID storeId, List<String> keywords) {
        if (keywords.isEmpty()) return 50;

        List<KeywordCoverageEntity> coverage = loadKeywordCoverage(storeId, keywords);

        if (coverage.isEmpty()) return 30;

        long coveredCount = coverage.stream()
                .filter(c -> Boolean.TRUE.equals(c.getInListing()) ||
                        Boolean.TRUE.equals(c.getInTitle()) ||
                        Boolean.TRUE.equals(c.getInBulletPoints()) ||
                        Boolean.TRUE.equals(c.getInBackend()))
                .count();

        return Math.min(100, (int) (coveredCount * 100 / keywords.size()));
    }

    protected List<KeywordCoverageEntity> loadKeywordCoverage(UUID storeId, List<String> keywords) {
        LambdaQueryWrapper<KeywordCoverageEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(KeywordCoverageEntity::getStoreId, storeId)
               .in(KeywordCoverageEntity::getKeywordText, keywords);
        return keywordCoverageMapper.selectList(wrapper);
    }
    /**
     * Returns the first word (>=3 chars) that appears 3 or more times in the
     * text, or null if none. Used to flag keyword over-repetition in titles.
     */
    private String findOverusedWord(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String token : text.toLowerCase().split("[^a-z0-9]+")) {
            if (token.length() < 3) {
                continue;
            }
            counts.merge(token, 1, Integer::sum);
        }
        return counts.entrySet().stream()
                .filter(e -> e.getValue() >= 3)
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse(null);
    }

    private void checkFieldForProhibitedWords(String field, String text, List<ComplianceCheckVo.ComplianceIssueVo> issues) {
        if (text == null) return;
        String lower = text.toLowerCase();
        for (String word : PROHIBITED_WORDS) {
            if (lower.contains(word)) {
                issues.add(ComplianceCheckVo.ComplianceIssueVo.builder()
                        .field(field)
                        .severity("high")
                        .rule("prohibited_word")
                        .message("Contains prohibited word: \"" + word + "\"")
                        .suggestion("Remove or replace \"" + word + "\" with compliant alternative")
                        .build());
            }
        }
    }

    @Override
    public Map<String, Object> getKeywordMapping(String productId) {
        UUID productUuid = UUID.fromString(productId);
        ProductEntity product = requireAccessibleProduct(productUuid);
        List<String> keywords = loadScoringKeywords(productUuid);

        // Load the latest listing content to check keyword presence
        LambdaQueryWrapper<ListingContentEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ListingContentEntity::getProductId, productUuid)
               .orderByDesc(ListingContentEntity::getUpdatedAt)
               .last("LIMIT 1");
        ListingContentEntity entity = listingContentMapper.selectOne(wrapper);

        // Build a combined text corpus from all listing fields
        StringBuilder corpus = new StringBuilder();
        if (entity != null) {
            if (entity.getTitle() != null) corpus.append(entity.getTitle()).append(" ");
            if (entity.getDescription() != null) corpus.append(entity.getDescription()).append(" ");
            if (entity.getBackendSearchTerms() != null) corpus.append(entity.getBackendSearchTerms()).append(" ");
            if (entity.getProductHighlights() != null) corpus.append(entity.getProductHighlights()).append(" ");
            List<String> bullets = parseJsonList(entity.getBulletPoints());
            for (String b : bullets) corpus.append(b).append(" ");
        } else {
            // No draft yet — check the product name at minimum
            if (product.getName() != null) {
                corpus.append(product.getName());
            }
        }

        String corpusLower = corpus.toString().toLowerCase();
        List<Map<String, Object>> covered = new ArrayList<>();
        List<Map<String, Object>> missing = new ArrayList<>();

        for (String kw : keywords) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("keyword", kw);
            if (corpusLower.contains(kw.toLowerCase())) {
                covered.add(entry);
            } else {
                missing.add(entry);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("covered", covered);
        result.put("missing", missing);
        result.put("total", keywords.size());
        result.put("coveredCount", covered.size());
        result.put("missingCount", missing.size());
        result.put("coveragePercent", keywords.isEmpty() ? 0 : (int) (covered.size() * 100.0 / keywords.size()));
        return result;
    }

    private ProductEntity requireAccessibleProduct(UUID productId) {
        ProductEntity product = productMapper.selectById(productId);
        if (product == null) {
            throw new BusinessException("PRODUCT_NOT_FOUND", "Product not found: " + productId);
        }
        if (product.getStoreId() == null) {
            throw new BusinessException("PRODUCT_STORE_MISSING", "Product is not assigned to a store");
        }
        storeService.getStoreById(product.getStoreId().toString());
        return product;
    }

    private ListingContentVo toVo(ListingContentEntity entity) {
        // Enrich with the product's display name + ASIN so the studio header
        // shows the real product instead of "产品 Listing · ASIN: N/A".
        String productName = null;
        String asin = null;
        if (entity.getProductId() != null) {
            ProductEntity product = productMapper.selectById(entity.getProductId());
            if (product != null) {
                productName = product.getName();
                asin = product.getAsin();
            }
        }
        return ListingContentVo.builder()
                .id(entity.getId().toString())
                .productId(entity.getProductId() != null ? entity.getProductId().toString() : null)
                .productName(productName)
                .asin(asin)
                .marketplaceId(entity.getMarketplaceId() != null ? entity.getMarketplaceId().toString() : null)
                .title(entity.getTitle())
                .bulletPoints(parseJsonList(entity.getBulletPoints()))
                .description(entity.getDescription())
                .backendSearchTerms(entity.getBackendSearchTerms())
                .productHighlights(entity.getProductHighlights())
                .attributes(parseAttributes(entity.getAttributes()))
                .subjectMatter(entity.getSubjectMatter())
                .intendedUse(entity.getIntendedUse())
                .targetAudience(entity.getTargetAudience())
                .status(entity.getStatus())
                .listingScore(entity.getListingScore() != null ? entity.getListingScore() : 0)
                .complianceScore(entity.getComplianceScore() != null ? entity.getComplianceScore() : 0)
                .seoScore(entity.getSeoScore() != null ? entity.getSeoScore() : 0)
                .conversionScore(entity.getConversionScore() != null ? entity.getConversionScore() : 0)
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().format(FORMATTER) : null)
                .build();
    }

    private List<String> parseJsonList(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse JSON list: {}", json, e);
            return new ArrayList<>();
        }
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize to JSON", e);
            return "[]";
        }
    }
}
