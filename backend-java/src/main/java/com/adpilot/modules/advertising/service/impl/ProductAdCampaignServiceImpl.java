package com.adpilot.modules.advertising.service.impl;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.common.security.CurrentUser;
import com.adpilot.common.security.DataScopeService;
import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.common.utils.UuidUtils;
import com.adpilot.modules.advertising.dto.CampaignCreateRequest;
import com.adpilot.modules.advertising.dto.CampaignHostingRequest;
import com.adpilot.modules.advertising.dto.HostingConfigRequest;
import com.adpilot.modules.advertising.dto.ProductAdCampaignRequest;
import com.adpilot.modules.advertising.entity.CampaignProductLinkEntity;
import com.adpilot.modules.advertising.entity.SafetyBoundaryEntity;
import com.adpilot.modules.advertising.hosting.ExecutionMode;
import com.adpilot.modules.advertising.hosting.HostingConfigService;
import com.adpilot.modules.advertising.mapper.CampaignProductLinkMapper;
import com.adpilot.modules.advertising.mapper.SafetyBoundaryMapper;
import com.adpilot.modules.advertising.operation.CreateOperationCommand;
import com.adpilot.modules.advertising.operation.OperationScope;
import com.adpilot.modules.advertising.operation.OperationService;
import com.adpilot.modules.advertising.operation.OperationSource;
import com.adpilot.modules.advertising.service.CampaignService;
import com.adpilot.modules.advertising.service.ProductAdCampaignService;
import com.adpilot.modules.advertising.support.BoundaryValidationResult;
import com.adpilot.modules.advertising.support.SafetyBoundaryLimit;
import com.adpilot.modules.advertising.support.SafetyBoundaryLimits;
import com.adpilot.modules.advertising.support.SafetyBoundaryValidator;
import com.adpilot.modules.advertising.support.StoreScopeRef;
import com.adpilot.modules.advertising.vo.CampaignVo;
import com.adpilot.modules.advertising.vo.ProductAdCampaignResultVo;
import com.adpilot.modules.rbac.PlatformFamily;
import com.adpilot.modules.store.entity.StoreEntity;
import com.adpilot.modules.store.mapper.StoreMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Orchestration service for the per-product AI ad creation modal (Req 1).
 *
 * <p>{@link #createProductAd} runs the whole flow in a single transaction
 * (Req 1.9): it validates the submitted inputs (Req 1.4), resolves the
 * Execution_Mode (Req 1.3), then —
 * <ol>
 *   <li>creates a keyword ad campaign through {@link CampaignService#createCampaign}
 *       (Req 1.5);</li>
 *   <li>links the campaign to the product (ASIN / local id) via the
 *       {@code campaign_product_links} table (Req 1.5);</li>
 *   <li>when {@code hostingEnabled} is true, persists the campaign-scoped
 *       Hosting_Config (carrying the resolved {@code execution_mode}) through
 *       {@link HostingConfigService#saveCampaignConfig} and persists the matching
 *       Safety_Boundary rows, additionally recording the campaign's Target_ACoS /
 *       Hosting_Goal through {@link CampaignService#assignHosting} (Req 1.6);</li>
 *   <li>enqueues the external platform write as a {@code platform_mutation}
 *       Operation + Outbox entry through {@link OperationService#createOperation}
 *       — the Amazon Ads API is never called on the request thread (Req 1.7).</li>
 * </ol>
 *
 * <p>Because the method is {@code @Transactional}, a failure in any persistence
 * step rolls the whole submission back so it takes effect in full or not at all
 * (Req 1.9). The returned {@link ProductAdCampaignResultVo} carries the new
 * campaign id, the linked product identifiers, and the resolved Execution_Mode
 * (Req 1.10).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductAdCampaignServiceImpl implements ProductAdCampaignService {

    /** Campaign type used for the per-product keyword ad campaign (Sponsored Products). */
    private static final String CAMPAIGN_TYPE_SP = "SP";

    /** Manual targeting — a per-product keyword ad campaign is manually targeted. */
    private static final String TARGETING_MANUAL = "manual";

    /** Operation {@code entity_type} for the created campaign. */
    private static final String ENTITY_TYPE_CAMPAIGN = "campaign";

    /**
     * Per-product keyword ad creation is confined to the {@code amazon} platform family
     * (Req 1.8). The orchestration endpoint is additionally guarded by
     * {@code @RequirePlatform(PlatformFamily.AMAZON)}; this is the store-level companion
     * check that the target store really belongs to the declared family.
     */
    private static final PlatformFamily REQUIRED_FAMILY = PlatformFamily.AMAZON;

    /** Reused to enforce bid/budget bound ordering (the only-tighten companion checks). */
    private final SafetyBoundaryValidator safetyBoundaryValidator;

    /** Reused to create the keyword ad campaign (Req 1.5). */
    private final CampaignService campaignService;

    /** Reused to persist the campaign↔product link (Req 1.5). */
    private final CampaignProductLinkMapper campaignProductLinkMapper;

    /** Reused to persist the campaign-scoped Hosting_Config with execution_mode (Req 1.6). */
    private final HostingConfigService hostingConfigService;

    /** Reused to persist the campaign-scoped Safety_Boundary rows (Req 1.6). */
    private final SafetyBoundaryMapper safetyBoundaryMapper;

    /** Reused to enqueue the external write as Operation + Outbox, never calling Amazon inline (Req 1.7). */
    private final OperationService operationService;

    /** Reused to enforce store-scope isolation before any persistence (Req 1.8). */
    private final DataScopeService dataScopeService;

    /** Reused to read the target store's platform_family for the cross-family check (Req 1.8). */
    private final StoreMapper storeMapper;

    @Override
    @Transactional
    public ProductAdCampaignResultVo createProductAd(ProductAdCampaignRequest request, String userId) {
        if (request == null) {
            throw new BusinessException(400, "INVALID_PRODUCT_AD_REQUEST", "Request body is required");
        }

        // Req 1.4 — authoritative input validation BEFORE any write happens.
        validateInputs(request);

        // Req 1.8 — store-scope and platform-family isolation BEFORE any persistence.
        // A store outside the caller's Store_Group_Scope, or one whose platform_family is
        // not amazon, is rejected with HTTP 403 and nothing is persisted.
        assertWriteScopeAndFamily(UuidUtils.fromString(request.getStoreId()));

        // Req 1.3 — resolve execution mode with observe_only fallback.
        ExecutionMode executionMode = resolveExecutionMode(request.getExecutionMode());

        // ── Single-transaction orchestration (Req 1.5–1.10). Any failure below rolls
        //    everything back so the submission is all-or-nothing (Req 1.9). ──────────

        // 1. Create the keyword ad campaign (Req 1.5).
        CampaignVo campaign = campaignService.createCampaign(buildCampaignRequest(request), userId);
        UUID campaignId = UuidUtils.fromString(campaign.getId());

        // 2. Link the campaign to the product / ASIN (Req 1.5).
        linkProduct(request, campaignId);

        // 3. When hosting is enabled, persist the Hosting_Config (incl. execution_mode)
        //    and the Safety_Boundary (Req 1.6).
        if (request.isHostingEnabled()) {
            persistHostingConfig(request, campaignId, executionMode, userId);
            persistSafetyBoundaries(request, campaignId, userId);
            if (request.getTargetAcos() != null) {
                campaignService.assignHosting(campaign.getId(), buildHostingRequest(request), userId);
            }
        }

        // 4. Enqueue the external platform write via Operation + Outbox (Req 1.7).
        //    The Amazon Ads API is NEVER called on the request thread — the OutboxWorker
        //    submits it asynchronously.
        enqueuePlatformWrite(request, campaign, campaignId, userId);

        log.info("Product ad campaign created: campaignId={}, hosting={}, executionMode={}",
                campaign.getId(), request.isHostingEnabled(), executionMode.value());

        // 5. Return the creation result (Req 1.10).
        return ProductAdCampaignResultVo.builder()
                .campaignId(campaign.getId())
                .productId(request.getProductId())
                .parentAsin(request.getParentAsin())
                .executionMode(executionMode.value())
                .build();
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Orchestration steps
    // ────────────────────────────────────────────────────────────────────────────

    /** Build the {@link CampaignCreateRequest} for the per-product keyword ad campaign (Req 1.5). */
    private CampaignCreateRequest buildCampaignRequest(ProductAdCampaignRequest request) {
        CampaignCreateRequest campaignRequest = new CampaignCreateRequest();
        campaignRequest.setStoreId(request.getStoreId());
        campaignRequest.setName(deriveCampaignName(request));
        campaignRequest.setCampaignType(CAMPAIGN_TYPE_SP);
        campaignRequest.setTargetingType(TARGETING_MANUAL);
        campaignRequest.setBudget(request.getBudget());
        if (request.getBudgetType() != null && !request.getBudgetType().isBlank()) {
            campaignRequest.setBudgetType(request.getBudgetType());
        }
        return campaignRequest;
    }

    /** Derive a human-readable campaign name from the product the ad serves. */
    private static String deriveCampaignName(ProductAdCampaignRequest request) {
        String product = !isBlank(request.getParentAsin())
                ? request.getParentAsin()
                : request.getProductId();
        return "Product Ad - " + product;
    }

    /** Insert the campaign↔product link row (Req 1.5). */
    private void linkProduct(ProductAdCampaignRequest request, UUID campaignId) {
        CampaignProductLinkEntity link = CampaignProductLinkEntity.builder()
                .campaignId(campaignId)
                .storeId(UuidUtils.fromString(request.getStoreId()))
                .parentAsin(request.getParentAsin())
                .productId(UuidUtils.fromString(request.getProductId()))
                .build();
        campaignProductLinkMapper.insert(link);
    }

    /** Persist the campaign-scoped Hosting_Config carrying the resolved execution_mode (Req 1.6). */
    private void persistHostingConfig(ProductAdCampaignRequest request, UUID campaignId,
                                      ExecutionMode executionMode, String userId) {
        HostingConfigRequest config = HostingConfigRequest.builder()
                .executionMode(executionMode.value())
                .build();
        hostingConfigService.saveCampaignConfig(
                UuidUtils.fromString(request.getStoreId()), campaignId, config, UuidUtils.fromString(userId));
    }

    /**
     * Persist the campaign-scoped Safety_Boundary rows for whichever bid/budget bounds
     * were supplied (Req 1.6). The Target_ACoS is persisted on the campaign itself via
     * {@link CampaignService#assignHosting} (it is not a {@link SafetyBoundaryLimit}).
     */
    private void persistSafetyBoundaries(ProductAdCampaignRequest request, UUID campaignId, String userId) {
        UUID storeId = UuidUtils.fromString(request.getStoreId());
        UUID createdBy = UuidUtils.fromString(userId);
        insertBoundary(storeId, campaignId, SafetyBoundaryLimit.MIN_BID, request.getBidMin(), createdBy);
        insertBoundary(storeId, campaignId, SafetyBoundaryLimit.MAX_BID, request.getBidMax(), createdBy);
        insertBoundary(storeId, campaignId, SafetyBoundaryLimit.MIN_DAILY_BUDGET, request.getBudgetMin(), createdBy);
        insertBoundary(storeId, campaignId, SafetyBoundaryLimit.MAX_DAILY_BUDGET, request.getBudgetMax(), createdBy);
    }

    /** Insert one campaign-scoped amount-typed Safety_Boundary row when {@code value} is present. */
    private void insertBoundary(UUID storeId, UUID campaignId, SafetyBoundaryLimit limit,
                                BigDecimal value, UUID createdBy) {
        if (value == null) {
            return;
        }
        SafetyBoundaryEntity boundary = SafetyBoundaryEntity.builder()
                .storeId(storeId)
                .scope("campaign")
                .scopeId(campaignId)
                .limitType(limit.name())
                .valueType(limit.valueType())
                .valueAmount(value)
                .comparisonSemantics(limit.comparisonSemantics().name().toLowerCase())
                .createdBy(createdBy)
                .build();
        safetyBoundaryMapper.insert(boundary);
    }

    /** Build the hosting (Target_ACoS / Hosting_Goal) request from the modal payload (Req 1.6). */
    private CampaignHostingRequest buildHostingRequest(ProductAdCampaignRequest request) {
        CampaignHostingRequest hostingRequest = new CampaignHostingRequest();
        hostingRequest.setTargetAcos(request.getTargetAcos());
        return hostingRequest;
    }

    /**
     * Enqueue the external platform write as a {@code platform_mutation} Operation +
     * Outbox entry (Req 1.7). This delegates entirely to {@link OperationService} which
     * persists the Operation and Outbox inside the transaction and leaves the actual
     * Amazon submission to the asynchronous OutboxWorker — nothing here calls Amazon.
     */
    private void enqueuePlatformWrite(ProductAdCampaignRequest request, CampaignVo campaign,
                                      UUID campaignId, String userId) {
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("campaign_id", campaign.getId());
        after.put("name", campaign.getName());
        after.put("campaign_type", CAMPAIGN_TYPE_SP);
        after.put("budget", request.getBudget());
        after.put("hosting_enabled", request.isHostingEnabled());

        operationService.createOperation(CreateOperationCommand.builder()
                .storeId(UuidUtils.fromString(request.getStoreId()))
                .operationSource(OperationSource.CREATION)
                .operationScope(OperationScope.PLATFORM_MUTATION)
                .entityType(ENTITY_TYPE_CAMPAIGN)
                .entityId(campaignId)
                .logicalIdempotencyKey("product-ad-campaign:" + campaign.getId())
                .afterValue(after)
                .reversible(false)
                .affectedCount(1)
                .build());
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Store-scope & platform-family isolation (Req 1.8)
    // ────────────────────────────────────────────────────────────────────────────

    /**
     * Enforce the store-scope and platform-family isolation that precedes every
     * per-product ad creation (Req 1.8). The caller must be able to write the target
     * store within their data scope, and the target store's {@code platform_family}
     * must be {@code amazon} (the declared family for this endpoint); a store outside
     * scope or of another family is rejected with HTTP 403 before any campaign,
     * product link, hosting config, safety boundary, or Operation/Outbox is persisted.
     *
     * <p>Mirrors {@code IndependentSiteWriteServiceImpl.assertWriteScopeAndFamily}.</p>
     */
    private void assertWriteScopeAndFamily(UUID storeId) {
        CurrentUser user = scopeUser();
        if (user != null) {
            // Store-scope isolation: reflection reads the storeId off the holder (Req 1.8).
            dataScopeService.assertCanWrite(StoreScopeRef.of(storeId), user);
        }
        // Platform-family isolation: the target store must belong to the amazon family.
        StoreEntity store = storeId != null ? storeMapper.selectById(storeId) : null;
        if (store == null || !isAmazonFamily(store.getPlatformFamily())) {
            log.info("Product ad creation refused (cross-family): store={} family={} expected={}",
                    storeId, store != null ? store.getPlatformFamily() : null, REQUIRED_FAMILY.getCode());
            throw forbiddenFamily(
                    "Store " + storeId + " is not an Amazon store; per-product ad creation is confined "
                            + "to the amazon platform family");
        }
    }

    private static boolean isAmazonFamily(String platformFamily) {
        if (platformFamily == null || platformFamily.isBlank()) {
            return false;
        }
        try {
            return PlatformFamily.fromCode(platformFamily) == REQUIRED_FAMILY;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static CurrentUser scopeUser() {
        return SecurityUtils.isAuthenticated() ? SecurityUtils.getCurrentUser() : null;
    }

    /** Req 1.8 — target store outside scope or of another platform family → forbidden (HTTP 403). */
    private static BusinessException forbiddenFamily(String message) {
        return new BusinessException(403, "PRODUCT_AD_CROSS_FAMILY", message);
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Input validation & execution-mode resolution
    // ────────────────────────────────────────────────────────────────────────────

    /**
     * Validate budget, target ACoS and bid/budget bounds (Req 1.4). Throws a
     * field-naming {@link BusinessException} (status 400) on the first violation,
     * before any record is created.
     */
    private void validateInputs(ProductAdCampaignRequest request) {
        // A campaign must be linkable to a product — require an ASIN or a local product id.
        if (isBlank(request.getProductId()) && isBlank(request.getParentAsin())) {
            throw badRequest("productId", "Either productId or parentAsin is required");
        }

        // Positivity checks (budget is mandatory; the bounds are optional).
        requirePositive(request.getBudget(), "budget", true);
        requirePositive(request.getTargetAcos(), "targetAcos", false);
        requirePositive(request.getBidMin(), "bidMin", false);
        requirePositive(request.getBidMax(), "bidMax", false);
        requirePositive(request.getBudgetMin(), "budgetMin", false);
        requirePositive(request.getBudgetMax(), "budgetMax", false);

        // Upper-bound-not-below-lower-bound checks, reusing SafetyBoundaryValidator's
        // cross-field ordering logic (minBid<=maxBid, minDailyBudget<=maxDailyBudget).
        SafetyBoundaryLimits.Builder bounds = SafetyBoundaryLimits.builder();
        if (request.getBidMin() != null) {
            bounds.minBid(request.getBidMin());
        }
        if (request.getBidMax() != null) {
            bounds.maxBid(request.getBidMax());
        }
        if (request.getBudgetMin() != null) {
            bounds.minDailyBudget(request.getBudgetMin());
        }
        if (request.getBudgetMax() != null) {
            bounds.maxDailyBudget(request.getBudgetMax());
        }

        BoundaryValidationResult result =
                safetyBoundaryValidator.validateCrossFieldConstraints(bounds.build());
        if (!result.valid()) {
            BoundaryValidationResult.ConstraintViolation violation = result.violations().get(0);
            throw badRequest(fieldNameForLimit(violation.limit()),
                    describeOrderingViolation(violation.limit()));
        }
    }

    /**
     * Resolve the Execution_Mode (Req 1.3): parse the raw value and fall back to
     * {@link ExecutionMode#DEFAULT} ({@code observe_only}) when {@code null},
     * blank, or unrecognized.
     */
    private ExecutionMode resolveExecutionMode(String raw) {
        ExecutionMode parsed = ExecutionMode.parse(raw);
        return parsed != null ? parsed : ExecutionMode.DEFAULT;
    }

    private void requirePositive(BigDecimal value, String field, boolean required) {
        if (value == null) {
            if (required) {
                throw badRequest(field, field + " is required");
            }
            return;
        }
        if (value.compareTo(BigDecimal.ZERO) <= 0) {
            throw badRequest(field, field + " must be positive");
        }
    }

    /** Map a Safety_Boundary limit back to the request field pair for clear error messages. */
    private static String fieldNameForLimit(SafetyBoundaryLimit limit) {
        return switch (limit) {
            case MIN_BID, MAX_BID -> "bidMax";
            case MIN_DAILY_BUDGET, MAX_DAILY_BUDGET -> "budgetMax";
            default -> limit.name();
        };
    }

    private static String describeOrderingViolation(SafetyBoundaryLimit limit) {
        return switch (limit) {
            case MIN_BID, MAX_BID -> "bidMax must be greater than or equal to bidMin";
            case MIN_DAILY_BUDGET, MAX_DAILY_BUDGET -> "budgetMax must be greater than or equal to budgetMin";
            default -> "upper bound must be greater than or equal to its lower bound";
        };
    }

    private static BusinessException badRequest(String field, String message) {
        return new BusinessException(400, "INVALID_PRODUCT_AD_REQUEST", message + " [field=" + field + "]");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
