package com.adpilot.modules.listing.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import lombok.Data;

@Data @Builder
public class ListingScoreVo {
    @JsonProperty("seo")
    private int seoScore;
    @JsonProperty("compliance")
    private int complianceScore;
    @JsonProperty("conversion")
    private int conversionScore;
    @JsonProperty("readability")
    private int readabilityScore;
    @JsonProperty("keywordCoverage")
    private int keywordCoverageScore;
    @JsonProperty("overall")
    private int listingScore;
    private String summary;
}
