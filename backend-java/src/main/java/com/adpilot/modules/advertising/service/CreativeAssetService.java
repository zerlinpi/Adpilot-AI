package com.adpilot.modules.advertising.service;

import com.adpilot.modules.advertising.dto.CreativeAssetCreateRequest;
import com.adpilot.modules.advertising.vo.CreativeAssetVo;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * Creative Asset Library (Req 29). Upload images/videos via
 * {@code FileStorageUtils}, list a store's assets, and search by
 * name/tag/ASIN/creator using the pure
 * {@link com.adpilot.modules.advertising.support.CreativeAssetFilter} predicate
 * (the same soundness/completeness contract Property 6 pins down).
 */
public interface CreativeAssetService {

    /**
     * List a store's creative assets, optionally narrowed by asset type and a
     * free-text search across name/tag/ASIN/creator (Req 29.1, 29.3).
     */
    List<CreativeAssetVo> listAssets(String storeId, String assetType, String search);

    /**
     * Store an uploaded asset via {@code FileStorageUtils} and persist its
     * record, returning the stored asset (Req 29.2).
     */
    CreativeAssetVo uploadAsset(CreativeAssetCreateRequest request, MultipartFile file);
}
