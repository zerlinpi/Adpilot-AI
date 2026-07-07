package com.adpilot.modules.advertising.vo;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response view for a single brand word (Req 22.1, 22.2).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BrandWordVo {

    @JsonProperty("id")
    private String id;

    @JsonProperty("store_id")
    private String storeId;

    @JsonProperty("word")
    private String word;

    @JsonProperty("match_type")
    private String matchType;

    @JsonProperty("created_at")
    private String createdAt;
}
