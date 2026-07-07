package com.adpilot.modules.apisync.oauth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response for the authorize-url endpoint: the fully-built Amazon authorize URL
 * the user is sent to, plus the opaque {@code state} generated for CSRF
 * validation and the resolved region.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AmazonAdsAuthUrlVo {
    private String authorizeUrl;
    private String state;
    private String region;
    private String storeId;
}
