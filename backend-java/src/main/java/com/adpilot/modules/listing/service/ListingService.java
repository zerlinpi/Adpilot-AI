package com.adpilot.modules.listing.service;

import com.adpilot.modules.listing.dto.ListingGenerateRequest;
import com.adpilot.modules.listing.vo.*;

public interface ListingService {
    ListingContentVo getListingContent(String productId);
    java.util.List<ListingContentVo> listVersions(String productId);
    ListingContentVo generateDraft(String productId, ListingGenerateRequest request, String userId);
    ListingScoreVo scoreListing(String productId);
    ComplianceCheckVo checkCompliance(String productId);
    ListingContentVo updateDraft(String id, ListingGenerateRequest request);
    ListingContentVo approveDraft(String id, String userId);
    java.util.Map<String, Object> getKeywordMapping(String productId);
}
