package com.adpilot.modules.listing.vo;
import lombok.Builder;
import lombok.Data;
import java.util.List;
import java.util.Map;

@Data @Builder
public class ListingContentVo {
    private String id;
    private String productId;
    private String productName;
    private String asin;
    private String marketplaceId;
    private String title;
    private List<String> bulletPoints;
    private String description;
    private String backendSearchTerms;
    private String productHighlights;
    private Map<String, String> attributes;
    private String subjectMatter;
    private String intendedUse;
    private String targetAudience;
    private String status;
    private int listingScore;
    private int complianceScore;
    private int seoScore;
    private int conversionScore;
    private String createdAt;
}
