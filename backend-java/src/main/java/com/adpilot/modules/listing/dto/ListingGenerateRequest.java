package com.adpilot.modules.listing.dto;
import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
public class ListingGenerateRequest {
    private String marketplaceId;
    /**
     * Optional platform/channel override (e.g. {@code amazon}, {@code shopify},
     * {@code woocommerce}, {@code independent_site}, {@code tiktok}). When absent,
     * the platform family is resolved server-side from the product's store, so the
     * frontend does not have to pass it. Provided values take precedence.
     */
    private String platform;
    private String targetAudience;
    private List<String> sellingPoints;
    private List<String> competitorAsins;
    private String tone;

    // Direct field overrides used when editing an AI/template-generated draft
    // before upload. When present these take precedence over regeneration.
    private String title;
    private List<String> bulletPoints;
    private String description;
    private String backendSearchTerms;
    private String productHighlights;
    private Map<String, String> attributes;
}
