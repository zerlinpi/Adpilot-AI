package com.adpilot.modules.apisync.oauth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Result of the OAuth callback: the originating store/region and the list of
 * advertising profiles/accounts the user may bind. The refresh token is stored
 * encrypted server-side and is intentionally NOT included here.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AmazonAdsCallbackVo {
    private String storeId;
    private String region;
    private List<AmazonAdsProfileVo> profiles;
}
