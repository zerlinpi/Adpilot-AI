package com.adpilot.modules.advertising.hosting;

import com.adpilot.modules.advertising.dto.BrandWordCreateRequest;
import com.adpilot.modules.advertising.vo.BrandWordVo;

import java.util.List;
import java.util.UUID;

/**
 * CRUD service for store brand-word lists (Req 22.1, 22.2, 22.6).
 *
 * <p>Brand words protect brand traffic: the V3 keyword engine never proposes a negative
 * keyword that matches a configured brand word. Mutations invalidate the
 * {@link BrandWordProtectionService} cache and are recorded in the audit log.</p>
 */
public interface BrandWordService {

    /**
     * List all brand words configured for a store (Req 22.2).
     */
    List<BrandWordVo> listBrandWords(UUID storeId);

    /**
     * Add a brand word for a store (Req 22.2). Validates the match type and records the
     * change in the audit log (Req 22.6).
     *
     * @param storeId the store
     * @param request the word and match type
     * @param actorId the authenticated user
     * @return the created brand word
     */
    BrandWordVo addBrandWord(UUID storeId, BrandWordCreateRequest request, UUID actorId);

    /**
     * Remove a brand word for a store (Req 22.2). Records the change in the audit log (Req 22.6).
     *
     * @param storeId     the store
     * @param brandWordId the brand word id to remove
     * @param actorId     the authenticated user
     */
    void deleteBrandWord(UUID storeId, UUID brandWordId, UUID actorId);
}
