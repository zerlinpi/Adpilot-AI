package com.adpilot.modules.advertising.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Payload for {@code POST /api/placement-locks} — create an Ad Placement Lock
 * strategy (Req 26.2). The service validates {@code bidMin <= bidMax} (Req 26.4)
 * and rejects the request with a {@code BusinessException} otherwise.
 */
@Data
public class AdPlacementLockCreateRequest {

    @NotBlank(message = "Store ID is required")
    private String storeId;

    @NotBlank(message = "Campaign ID is required")
    private String campaignId;

    /**
     * Placement strategy type/key. One of the preset SparkX targets
     * ({@code top_of_search_1_1} 首页首位, {@code rest_of_search} 首页其余,
     * {@code product_pages} 商品页面) or any custom key the operator enters.
     * Stored verbatim in {@code target_placement} (max 40 chars).
     */
    @NotBlank(message = "Target placement is required")
    @Size(max = 40, message = "Target placement must be at most 40 characters")
    private String targetPlacement;

    @NotNull(message = "Minimum bid is required")
    @PositiveOrZero(message = "Minimum bid must be zero or greater")
    private BigDecimal bidMin;

    @NotNull(message = "Maximum bid is required")
    @PositiveOrZero(message = "Maximum bid must be zero or greater")
    private BigDecimal bidMax;
}
