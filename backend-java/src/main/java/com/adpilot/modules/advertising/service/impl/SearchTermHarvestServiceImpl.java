package com.adpilot.modules.advertising.service.impl;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.common.security.CurrentUser;
import com.adpilot.common.security.DataScopeService;
import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.advertising.converter.SearchTermConverter;
import com.adpilot.modules.advertising.dto.SearchTermHarvestRequest;
import com.adpilot.modules.advertising.entity.AdGroupEntity;
import com.adpilot.modules.advertising.entity.CampaignEntity;
import com.adpilot.modules.advertising.entity.KeywordEntity;
import com.adpilot.modules.advertising.entity.NegativeKeywordEntity;
import com.adpilot.modules.advertising.entity.SearchTermEntity;
import com.adpilot.modules.advertising.mapper.AdGroupMapper;
import com.adpilot.modules.advertising.mapper.CampaignMapper;
import com.adpilot.modules.advertising.mapper.KeywordMapper;
import com.adpilot.modules.advertising.mapper.NegativeKeywordMapper;
import com.adpilot.modules.advertising.mapper.SearchTermMapper;
import com.adpilot.modules.advertising.service.HarvestAction;
import com.adpilot.modules.advertising.service.SearchTermHarvestService;
import com.adpilot.modules.advertising.vo.SearchTermVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Default {@link SearchTermHarvestService} (Req 13).
 *
 * <p>The previous implementation always produced a positive enabled Keyword
 * regardless of the chosen action and required the caller to supply the target.
 * This implementation derives the target Campaign/Ad_Group from the Search_Term
 * when no override is given (Req 13.1), validates an override against the
 * caller's effective data scope (Req 13.2), and routes by {@link HarvestAction}
 * so a negative is never materialised as a positive keyword (Req 13.5).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SearchTermHarvestServiceImpl implements SearchTermHarvestService {

    private final SearchTermMapper searchTermMapper;
    private final CampaignMapper campaignMapper;
    private final AdGroupMapper adGroupMapper;
    private final KeywordMapper keywordMapper;
    private final NegativeKeywordMapper negativeKeywordMapper;
    private final DataScopeService dataScopeService;

    /** Current authenticated user, or {@code null} for system/background contexts. */
    private static CurrentUser scopeUser() {
        return SecurityUtils.isAuthenticated() ? SecurityUtils.getCurrentUser() : null;
    }

    @Override
    @Transactional
    public SearchTermVo harvest(String searchTermId, SearchTermHarvestRequest request, String userId) {
        SearchTermEntity searchTerm = searchTermMapper.selectById(parseId(searchTermId, "SEARCH_TERM_NOT_FOUND",
                "Search term not found: " + searchTermId));
        if (searchTerm == null) {
            throw new BusinessException("SEARCH_TERM_NOT_FOUND", "Search term not found: " + searchTermId);
        }

        // Req 13.7 — reject any action that is not one of the four supported actions,
        // naming the invalid action in the error.
        HarvestAction action = HarvestAction.fromValue(request.getHarvestAction())
                .orElseThrow(() -> new BusinessException(
                        "INVALID_HARVEST_ACTION",
                        "Invalid harvest action: " + request.getHarvestAction()));

        CurrentUser user = scopeUser();

        // Req 13.1/13.2 — resolve the target campaign (override when supplied, else derived).
        CampaignEntity campaign = resolveCampaign(searchTerm, request, user);

        // Resolve the target ad group (override when supplied, else derived). May be
        // null when neither an override nor the term provides one; keyword actions
        // require it (Req 13.8), negative actions can fall back to the campaign (Req 13.5).
        AdGroupEntity adGroup = resolveAdGroup(searchTerm, request, campaign, user);

        UUID actingUser = parseUserId(userId);

        switch (action) {
            case ADD_EXACT -> createKeyword(searchTerm, campaign, adGroup, "exact", request, actingUser);
            case ADD_PHRASE -> createKeyword(searchTerm, campaign, adGroup, "phrase", request, actingUser);
            case ADD_NEGATIVE -> createNegativeKeyword(searchTerm, campaign, adGroup, actingUser);
            case WATCHLIST -> {
                // Req 13.6 — record a watch only; create no Keyword or Negative_Keyword.
            }
        }

        searchTerm.setHarvestingStatus(action.value());
        // A watch is not a harvest into a targeting object; only the producing
        // actions flip the harvested flag.
        searchTerm.setHarvested(action != HarvestAction.WATCHLIST);
        searchTerm.setUpdatedAt(LocalDateTime.now());
        searchTermMapper.updateById(searchTerm);

        log.info("Search term harvested: searchTermId={}, action={}, campaignId={}, adGroupId={}",
                searchTermId, action.value(), campaign.getId(),
                adGroup != null ? adGroup.getId() : null);

        return SearchTermConverter.toVo(searchTerm);
    }

    /**
     * Resolve the target campaign: the override when supplied (validated to exist
     * and to be within the caller's effective data scope, Req 13.2), otherwise the
     * campaign associated with the Search_Term (Req 13.1). Rejects when neither
     * yields a resolvable campaign (Req 13.8).
     */
    private CampaignEntity resolveCampaign(SearchTermEntity searchTerm, SearchTermHarvestRequest request, CurrentUser user) {
        String override = request.getTargetCampaignId();
        if (override != null && !override.isBlank()) {
            CampaignEntity campaign = campaignMapper.selectById(parseId(override, "HARVEST_TARGET_UNRESOLVABLE",
                    "Override target campaign could not be resolved: " + override));
            if (campaign == null) {
                throw new BusinessException("HARVEST_TARGET_UNRESOLVABLE",
                        "Override target campaign could not be resolved: " + override);
            }
            if (user != null) {
                dataScopeService.assertCanWrite(campaign, user);
            }
            return campaign;
        }
        if (searchTerm.getCampaignId() == null) {
            throw new BusinessException("HARVEST_TARGET_UNRESOLVABLE",
                    "Target campaign could not be derived from the search term");
        }
        CampaignEntity derived = campaignMapper.selectById(searchTerm.getCampaignId());
        if (derived == null) {
            throw new BusinessException("HARVEST_TARGET_UNRESOLVABLE",
                    "Target campaign could not be derived from the search term");
        }
        return derived;
    }

    /**
     * Resolve the target ad group: the override when supplied (validated to exist,
     * to be within the caller's effective data scope, and to belong to the resolved
     * campaign, Req 13.2), otherwise the ad group associated with the Search_Term
     * (Req 13.1). May return {@code null} when none is available.
     */
    private AdGroupEntity resolveAdGroup(SearchTermEntity searchTerm, SearchTermHarvestRequest request,
                                         CampaignEntity campaign, CurrentUser user) {
        String override = request.getTargetAdGroupId();
        if (override != null && !override.isBlank()) {
            AdGroupEntity adGroup = adGroupMapper.selectById(parseId(override, "HARVEST_TARGET_UNRESOLVABLE",
                    "Override target ad group could not be resolved: " + override));
            if (adGroup == null) {
                throw new BusinessException("HARVEST_TARGET_UNRESOLVABLE",
                        "Override target ad group could not be resolved: " + override);
            }
            if (user != null) {
                dataScopeService.assertCanWrite(adGroup, user);
            }
            if (!campaign.getId().equals(adGroup.getCampaignId())) {
                throw new BusinessException("HARVEST_TARGET_UNRESOLVABLE",
                        "Override target ad group does not belong to the resolved campaign");
            }
            return adGroup;
        }
        if (searchTerm.getAdGroupId() == null) {
            return null;
        }
        return adGroupMapper.selectById(searchTerm.getAdGroupId());
    }

    /**
     * Create an enabled Keyword with the given match type in the resolved ad group
     * (Req 13.3/13.4). Rejects when the ad group could not be resolved (Req 13.8),
     * since a keyword cannot exist without one.
     */
    private void createKeyword(SearchTermEntity searchTerm, CampaignEntity campaign, AdGroupEntity adGroup,
                               String matchType, SearchTermHarvestRequest request, UUID actingUser) {
        if (adGroup == null) {
            throw new BusinessException("HARVEST_TARGET_UNRESOLVABLE",
                    "Target ad group could not be resolved for keyword creation");
        }
        String resolvedMatchType = (request.getMatchType() != null && !request.getMatchType().isBlank())
                ? request.getMatchType()
                : matchType;
        KeywordEntity keyword = KeywordEntity.builder()
                .campaignId(campaign.getId())
                .adGroupId(adGroup.getId())
                .storeId(searchTerm.getStoreId())
                .keywordText(searchTerm.getSearchTerm())
                .matchType(resolvedMatchType)
                .status("enabled")
                .bid(resolveBid(request, adGroup))
                .createdBy(actingUser)
                .updatedBy(actingUser)
                .build();
        keywordMapper.insert(keyword);
        searchTerm.setKeywordId(keyword.getId());
        log.info("Harvested keyword created: keywordId={}, matchType={}", keyword.getId(), resolvedMatchType);
    }

    /**
     * Create a Negative_Keyword for the resolved Campaign or Ad_Group and NEVER a
     * positive Keyword (Req 13.5). The negative is recorded at campaign level,
     * carrying the resolved ad group when available for traceability.
     */
    private void createNegativeKeyword(SearchTermEntity searchTerm, CampaignEntity campaign,
                                       AdGroupEntity adGroup, UUID actingUser) {
        NegativeKeywordEntity negative = NegativeKeywordEntity.builder()
                .campaignId(campaign.getId())
                .adGroupId(adGroup != null ? adGroup.getId() : null)
                .storeId(searchTerm.getStoreId())
                .keywordText(searchTerm.getSearchTerm())
                .matchType("negativeExact")
                .level(adGroup != null ? "adGroup" : "campaign")
                .source("search_term_harvest")
                .status("enabled")
                .createdBy(actingUser)
                .updatedBy(actingUser)
                .build();
        negativeKeywordMapper.insert(negative);
        log.info("Harvested negative keyword created: negativeId={}, level={}", negative.getId(), negative.getLevel());
    }

    /** The explicit bid, else the ad group default bid, else null. */
    private BigDecimal resolveBid(SearchTermHarvestRequest request, AdGroupEntity adGroup) {
        if (request.getBid() != null && !request.getBid().isBlank()) {
            try {
                return new BigDecimal(request.getBid());
            } catch (NumberFormatException e) {
                throw new BusinessException("INVALID_BID", "Invalid bid value: " + request.getBid());
            }
        }
        return adGroup.getDefaultBid();
    }

    private static UUID parseId(String raw, String errorCode, String errorMessage) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(errorCode, errorMessage);
        }
    }

    private static UUID parseUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(userId);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
