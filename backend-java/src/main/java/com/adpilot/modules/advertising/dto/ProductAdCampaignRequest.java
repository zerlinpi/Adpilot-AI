package com.adpilot.modules.advertising.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Payload for {@code POST /api/product-ads/campaign} — the per-product AI ad
 * creation modal (Req 1.2). Carries everything the orchestration endpoint needs
 * to, in a single transaction, create a keyword Campaign, link it to the
 * product (ASIN), and — when {@code hostingEnabled} is true — persist the
 * Hosting_Config and Safety_Boundary.
 *
 * <p>Bean Validation enforces the field-level constraints of Req 1.4: the budget
 * and any supplied target ACoS / bid bounds / budget bounds must be positive.
 * The cross-field rule (each upper bound must be no smaller than its lower
 * bound) is enforced authoritatively in {@code ProductAdCampaignService}, which
 * also rejects the submission without persisting any record.
 */
@Data
public class ProductAdCampaignRequest {

    /** Store the product (and resulting campaign) belongs to; required. */
    @NotBlank(message = "Store ID is required")
    private String storeId;

    /** Local product id to link the campaign to (optional if {@code parentAsin} is given). */
    private String productId;

    /** Parent ASIN to link the campaign to (optional if {@code productId} is given). */
    private String parentAsin;

    /** Daily/lifetime budget amount; required and must be positive (Req 1.4). */
    @NotNull(message = "Budget is required")
    @DecimalMin(value = "0.0", inclusive = false, message = "Budget must be positive")
    private BigDecimal budget;

    /** Budget type ({@code daily} / {@code lifetime}); defaults to {@code daily} when omitted. */
    private String budgetType;

    /** AI personality (保守 / 均衡 / 激进 → conservative / balanced / aggressive). */
    private String personality;

    /** Whether the campaign is placed under AI hosting; defaults to {@code false}. */
    private boolean hostingEnabled;

    /** Target ACoS as a percentage; when supplied must be positive (Req 1.4). */
    @DecimalMin(value = "0.0", inclusive = false, message = "Target ACoS must be positive")
    private BigDecimal targetAcos;

    /** Lower bid bound; when supplied must be positive (Req 1.4). */
    @DecimalMin(value = "0.0", inclusive = false, message = "Bid minimum must be positive")
    private BigDecimal bidMin;

    /** Upper bid bound; when supplied must be positive and not less than {@code bidMin} (Req 1.4). */
    @DecimalMin(value = "0.0", inclusive = false, message = "Bid maximum must be positive")
    private BigDecimal bidMax;

    /** Lower budget bound; when supplied must be positive (Req 1.4). */
    @DecimalMin(value = "0.0", inclusive = false, message = "Budget minimum must be positive")
    private BigDecimal budgetMin;

    /** Upper budget bound; when supplied must be positive and not less than {@code budgetMin} (Req 1.4). */
    @DecimalMin(value = "0.0", inclusive = false, message = "Budget maximum must be positive")
    private BigDecimal budgetMax;

    /**
     * Execution mode (Execution_Mode); optional. When omitted/blank/unrecognised
     * the service falls back to {@code ExecutionMode.DEFAULT} ({@code observe_only}, Req 1.3).
     */
    private String executionMode;
}
