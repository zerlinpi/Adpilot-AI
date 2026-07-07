package com.adpilot.modules.advertising.controller;

import com.adpilot.common.api.ApiResponse;
import com.adpilot.common.exception.BusinessException;
import com.adpilot.common.security.RequirePermission;
import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.advertising.dto.BrandWordCreateRequest;
import com.adpilot.modules.advertising.dto.HostingConfigRequest;
import com.adpilot.modules.advertising.entity.CampaignEntity;
import com.adpilot.modules.advertising.entity.GoalEntity;
import com.adpilot.modules.advertising.hosting.BrandWordService;
import com.adpilot.modules.advertising.hosting.CanaryRolloutService;
import com.adpilot.modules.advertising.hosting.HostingConfigService;
import com.adpilot.modules.advertising.hosting.HostingOrgIsolationGuard;
import com.adpilot.modules.advertising.vo.BrandWordVo;
import com.adpilot.modules.advertising.vo.CanaryRolloutVo;
import com.adpilot.modules.advertising.vo.HostingConfigVo;
import com.adpilot.modules.store.entity.StoreEntity;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.LinkedHashSet;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Hosting configuration and brand-word APIs under {@code /api/advertising/hosting}
 * (Req 21, 12, 22).
 *
 * <p>Permissions follow Req 21.6: {@code advertising:manage} for reading hosting config and
 * {@code advertising:execute} for modifying it. Brand-word reads require
 * {@code advertising:view}; mutations require {@code advertising:execute}.</p>
 *
 * <p>Cross-organization id isolation (resolving every inbound id to a store in the caller's
 * org) is layered on by the dedicated isolation guard (task 24.3) in addition to the
 * permission checks here.</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/advertising/hosting")
@RequiredArgsConstructor
public class HostingConfigController {

    private final HostingConfigService hostingConfigService;
    private final BrandWordService brandWordService;
    private final CanaryRolloutService canaryRolloutService;
    private final HostingOrgIsolationGuard orgIsolationGuard;

    // ────────────────────────────────────────────────────────────────────────────
    // Hosting config (Req 21.1, 21.2, 21.5, 21.6)
    // ────────────────────────────────────────────────────────────────────────────

    /**
     * GET /api/advertising/hosting/config/{storeId} — load store hosting config (Req 21.1).
     */
    @GetMapping("/config/{storeId}")
    @RequirePermission("advertising:manage")
    public ApiResponse<HostingConfigVo> getStoreConfig(@PathVariable String storeId) {
        UUID id = parseUuid(storeId, "storeId");
        // Cross-org isolation (Req 39): resolve the store to the caller's org before reading.
        orgIsolationGuard.resolveStoreInCallerOrg(id);
        return ApiResponse.ok(hostingConfigService.getConfig(id, "store", id));
    }

    /**
     * PUT /api/advertising/hosting/config/{storeId} — validate and persist store config
     * (Req 21.2, 21.3, 21.4, 12.2, 12.6).
     */
    @PutMapping("/config/{storeId}")
    @RequirePermission("advertising:execute")
    public ApiResponse<HostingConfigVo> putStoreConfig(@PathVariable String storeId,
                                                       @Valid @RequestBody HostingConfigRequest request) {
        UUID id = parseUuid(storeId, "storeId");
        // Cross-org isolation (Req 39): resolve the store to the caller's org before mutating.
        orgIsolationGuard.resolveStoreInCallerOrg(id);
        UUID actorId = currentActor();
        HostingConfigVo saved = hostingConfigService.saveStoreConfig(id, request, actorId);
        log.info("Hosting config updated for store={}", id);
        return ApiResponse.ok(saved);
    }

    /**
     * GET /api/advertising/hosting/config/{storeId}/goals/{goalId} — goal-level override (Req 21.5).
     */
    @GetMapping("/config/{storeId}/goals/{goalId}")
    @RequirePermission("advertising:manage")
    public ApiResponse<HostingConfigVo> getGoalConfig(@PathVariable String storeId,
                                                      @PathVariable String goalId) {
        UUID store = parseUuid(storeId, "storeId");
        UUID goal = parseUuid(goalId, "goalId");
        // Cross-org isolation (Req 39): both ids must resolve into the caller's org, and the
        // goal must belong to the named store.
        orgIsolationGuard.resolveStoreInCallerOrg(store);
        GoalEntity resolvedGoal = orgIsolationGuard.resolveGoalInCallerOrg(goal);
        if (!store.equals(resolvedGoal.getStoreId())) {
            throw new BusinessException(404, "HOSTING_RESOURCE_NOT_FOUND",
                    "goalId does not belong to storeId");
        }
        return ApiResponse.ok(hostingConfigService.getConfig(store, "goal", goal));
    }

    /**
     * GET /api/advertising/hosting/config/{storeId}/campaigns/{campaignId} — campaign-level
     * override (Req 21.5).
     */
    @GetMapping("/config/{storeId}/campaigns/{campaignId}")
    @RequirePermission("advertising:manage")
    public ApiResponse<HostingConfigVo> getCampaignConfig(@PathVariable String storeId,
                                                          @PathVariable String campaignId) {
        UUID store = parseUuid(storeId, "storeId");
        UUID campaign = parseUuid(campaignId, "campaignId");
        // Cross-org isolation (Req 39): both ids must resolve into the caller's org, and the
        // campaign must belong to the named store.
        orgIsolationGuard.resolveStoreInCallerOrg(store);
        CampaignEntity resolvedCampaign = orgIsolationGuard.resolveCampaignInCallerOrg(campaign);
        if (!store.equals(resolvedCampaign.getStoreId())) {
            throw new BusinessException(404, "HOSTING_RESOURCE_NOT_FOUND",
                    "campaignId does not belong to storeId");
        }
        return ApiResponse.ok(hostingConfigService.getConfig(store, "campaign", campaign));
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Brand-word CRUD (Req 22.2, 22.6)
    // ────────────────────────────────────────────────────────────────────────────

    /**
     * GET /api/advertising/hosting/brand-words/{storeId} — list brand words (Req 22.2).
     */
    @GetMapping("/brand-words/{storeId}")
    @RequirePermission("advertising:view")
    public ApiResponse<List<BrandWordVo>> listBrandWords(@PathVariable String storeId) {
        UUID id = parseUuid(storeId, "storeId");
        orgIsolationGuard.resolveStoreInCallerOrg(id);
        return ApiResponse.ok(brandWordService.listBrandWords(id));
    }

    /**
     * POST /api/advertising/hosting/brand-words/{storeId} — add a brand word (Req 22.2, 22.6).
     */
    @PostMapping("/brand-words/{storeId}")
    @RequirePermission("advertising:execute")
    public ApiResponse<BrandWordVo> addBrandWord(@PathVariable String storeId,
                                                 @Valid @RequestBody BrandWordCreateRequest request) {
        UUID id = parseUuid(storeId, "storeId");
        orgIsolationGuard.resolveStoreInCallerOrg(id);
        UUID actorId = currentActor();
        BrandWordVo created = brandWordService.addBrandWord(id, request, actorId);
        log.info("Brand word added for store={}", id);
        return ApiResponse.ok(created);
    }

    /**
     * DELETE /api/advertising/hosting/brand-words/{storeId}/{wordId} — remove a brand word
     * (Req 22.2, 22.6).
     */
    @DeleteMapping("/brand-words/{storeId}/{wordId}")
    @RequirePermission("advertising:execute")
    public ApiResponse<Void> deleteBrandWord(@PathVariable String storeId,
                                             @PathVariable String wordId) {
        UUID store = parseUuid(storeId, "storeId");
        UUID word = parseUuid(wordId, "wordId");
        orgIsolationGuard.resolveStoreInCallerOrg(store);
        UUID actorId = currentActor();
        brandWordService.deleteBrandWord(store, word, actorId);
        log.info("Brand word removed for store={} id={}", store, word);
        return ApiResponse.ok(null);
    }

    // ------------------------------------------------------------------
    // Canary rollout controls (Req 35.2)
    // ------------------------------------------------------------------

    /**
     * GET /api/advertising/hosting/canary/{storeId} - load org-level canary state.
     *
     * <p>The path store is used to resolve and authorize the caller's organization.</p>
     */
    @GetMapping("/canary/{storeId}")
    @RequirePermission("advertising:manage")
    public ApiResponse<CanaryRolloutVo> getCanary(@PathVariable String storeId) {
        StoreEntity store = orgIsolationGuard.resolveStoreInCallerOrg(parseUuid(storeId, "storeId"));
        return ApiResponse.ok(toCanaryVo(store.getOrgId()));
    }

    /**
     * POST /api/advertising/hosting/canary/{storeId}/enable - enable org-level canary.
     */
    @PostMapping("/canary/{storeId}/enable")
    @RequirePermission("advertising:execute")
    public ApiResponse<CanaryRolloutVo> enableCanary(@PathVariable String storeId) {
        StoreEntity store = orgIsolationGuard.resolveStoreInCallerOrg(parseUuid(storeId, "storeId"));
        canaryRolloutService.enableCanary(store.getOrgId(), currentActor());
        return ApiResponse.ok(toCanaryVo(store.getOrgId()));
    }

    /**
     * POST /api/advertising/hosting/canary/{storeId}/disable - disable org-level canary.
     */
    @PostMapping("/canary/{storeId}/disable")
    @RequirePermission("advertising:execute")
    public ApiResponse<CanaryRolloutVo> disableCanary(@PathVariable String storeId) {
        StoreEntity store = orgIsolationGuard.resolveStoreInCallerOrg(parseUuid(storeId, "storeId"));
        canaryRolloutService.disableCanary(store.getOrgId(), currentActor());
        return ApiResponse.ok(toCanaryVo(store.getOrgId()));
    }

    /**
     * POST /api/advertising/hosting/canary/{storeId}/stores/{targetStoreId} - include a store.
     */
    @PostMapping("/canary/{storeId}/stores/{targetStoreId}")
    @RequirePermission("advertising:execute")
    public ApiResponse<CanaryRolloutVo> addCanaryStore(@PathVariable String storeId,
                                                       @PathVariable String targetStoreId) {
        StoreEntity scopeStore = orgIsolationGuard.resolveStoreInCallerOrg(parseUuid(storeId, "storeId"));
        StoreEntity targetStore = orgIsolationGuard.resolveStoreInCallerOrg(parseUuid(targetStoreId, "targetStoreId"));
        if (!scopeStore.getOrgId().equals(targetStore.getOrgId())) {
            throw new BusinessException(404, "HOSTING_RESOURCE_NOT_FOUND",
                    "targetStoreId does not belong to the same organization");
        }
        canaryRolloutService.addStoreToCanary(scopeStore.getOrgId(), targetStore.getId(), currentActor());
        return ApiResponse.ok(toCanaryVo(scopeStore.getOrgId()));
    }

    /**
     * DELETE /api/advertising/hosting/canary/{storeId}/stores/{targetStoreId} - remove a store.
     */
    @DeleteMapping("/canary/{storeId}/stores/{targetStoreId}")
    @RequirePermission("advertising:execute")
    public ApiResponse<CanaryRolloutVo> removeCanaryStore(@PathVariable String storeId,
                                                          @PathVariable String targetStoreId) {
        StoreEntity scopeStore = orgIsolationGuard.resolveStoreInCallerOrg(parseUuid(storeId, "storeId"));
        StoreEntity targetStore = orgIsolationGuard.resolveStoreInCallerOrg(parseUuid(targetStoreId, "targetStoreId"));
        if (!scopeStore.getOrgId().equals(targetStore.getOrgId())) {
            throw new BusinessException(404, "HOSTING_RESOURCE_NOT_FOUND",
                    "targetStoreId does not belong to the same organization");
        }
        canaryRolloutService.removeStoreFromCanary(scopeStore.getOrgId(), targetStore.getId(), currentActor());
        return ApiResponse.ok(toCanaryVo(scopeStore.getOrgId()));
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Helpers
    // ────────────────────────────────────────────────────────────────────────────

    private static UUID parseUuid(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(400, "HOSTING_INVALID_PARAMETER", field + " must not be blank");
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException(400, "HOSTING_INVALID_PARAMETER",
                    "Invalid " + field + " '" + value + "': not a valid identifier");
        }
    }

    private static UUID currentActor() {
        String userId = SecurityUtils.getCurrentUserIdOrNull();
        return userId == null ? null : UUID.fromString(userId);
    }

    private CanaryRolloutVo toCanaryVo(UUID orgId) {
        boolean enabled = canaryRolloutService.isCanaryEnabled(orgId);
        LinkedHashSet<String> storeIds = canaryRolloutService.getCanaryStores(orgId).stream()
                .map(UUID::toString)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return CanaryRolloutVo.builder()
                .orgId(orgId == null ? null : orgId.toString())
                .enabled(enabled)
                .storeIds(storeIds)
                .build();
    }
}
