package com.adpilot.modules.advertising.controller;

import com.adpilot.common.api.ApiResponse;
import com.adpilot.common.security.RequirePermission;
import com.adpilot.modules.advertising.dto.CreativeAssetCreateRequest;
import com.adpilot.modules.advertising.service.CreativeAssetService;
import com.adpilot.modules.advertising.vo.CreativeAssetVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Creative Asset Library endpoints (Req 29). List/search a store's reusable
 * images and videos, and upload new assets via multipart (stored with
 * {@code FileStorageUtils}). Search by name/tag/ASIN/creator is delegated to the
 * pure {@code CreativeAssetFilter} predicate (the same soundness/completeness
 * contract Property 6 pins down).
 *
 * <p>Errors propagate to {@code GlobalExceptionHandler}, which serializes a JSON
 * error envelope with a 4xx/5xx status — never an HTML page (Req 1).
 */
@Slf4j
@RestController
@RequestMapping("/api/creative-assets")
@RequiredArgsConstructor
public class CreativeAssetController {

    private final CreativeAssetService creativeAssetService;

    /**
     * GET /api/creative-assets - List a store's creative assets, optionally
     * narrowed by {@code assetType} and a free-text {@code search} across
     * name/tag/ASIN/creator (Req 29.1, 29.3).
     */
    @GetMapping
    @RequirePermission("advertising:view")
    public ApiResponse<List<CreativeAssetVo>> listAssets(
            @RequestParam(required = false) String storeId,
            @RequestParam(required = false) String assetType,
            @RequestParam(required = false) String search) {
        return ApiResponse.ok(creativeAssetService.listAssets(storeId, assetType, search));
    }

    /**
     * POST /api/creative-assets - Upload a creative asset (multipart). The binary
     * is the {@code file} part; the remaining form fields describe the asset
     * (Req 29.2).
     */
    @PostMapping(consumes = "multipart/form-data")
    @RequirePermission("advertising:manage")
    public ApiResponse<CreativeAssetVo> uploadAsset(
            @RequestParam("file") MultipartFile file,
            @RequestParam("storeId") String storeId,
            @RequestParam("name") String name,
            @RequestParam(value = "assetType", required = false) String assetType,
            @RequestParam(value = "mediaKind", required = false) String mediaKind,
            @RequestParam(value = "asin", required = false) String asin,
            @RequestParam(value = "tags", required = false) String tags) {
        CreativeAssetCreateRequest request = new CreativeAssetCreateRequest();
        request.setStoreId(storeId);
        request.setName(name);
        request.setAssetType(assetType);
        request.setMediaKind(mediaKind);
        request.setAsin(asin);
        request.setTags(parseTags(tags));

        CreativeAssetVo asset = creativeAssetService.uploadAsset(request, file);
        log.info("Creative asset created: {}", asset.getId());
        return ApiResponse.ok(asset);
    }

    /** Split a comma-separated tag string into a trimmed, non-empty tag list. */
    private List<String> parseTags(String tags) {
        if (tags == null || tags.isBlank()) {
            return List.of();
        }
        return Arrays.stream(tags.split(","))
                .map(String::trim)
                .filter(t -> !t.isEmpty())
                .collect(Collectors.toList());
    }
}
