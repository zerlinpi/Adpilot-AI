package com.adpilot.modules.advertising.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request body for {@code POST /api/advertising/hosting/brand-words/{storeId}} (Req 22.2).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BrandWordCreateRequest {

    /** The brand word to protect; never proposed as a negative keyword. */
    @NotBlank(message = "word must not be blank")
    @Size(max = 255, message = "word must be at most 255 characters")
    @JsonProperty("word")
    private String word;

    /**
     * Match type: {@code exact} (full match) or {@code contains} (substring match).
     * Defaults to {@code exact} when omitted (Req 22.1, 22.3).
     */
    @JsonProperty("match_type")
    private String matchType;
}
