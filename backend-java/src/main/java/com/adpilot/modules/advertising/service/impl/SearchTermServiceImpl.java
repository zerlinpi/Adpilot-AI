package com.adpilot.modules.advertising.service.impl;

import com.adpilot.common.api.PageResponse;
import com.adpilot.common.security.CurrentUser;
import com.adpilot.common.security.DataScopeService;
import com.adpilot.common.security.ScopeTarget;
import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.advertising.converter.SearchTermConverter;
import com.adpilot.modules.advertising.dto.SearchTermHarvestRequest;
import com.adpilot.modules.advertising.entity.SearchTermEntity;
import com.adpilot.modules.advertising.mapper.SearchTermMapper;
import com.adpilot.modules.advertising.service.SearchTermHarvestService;
import com.adpilot.modules.advertising.service.SearchTermService;
import com.adpilot.modules.advertising.vo.SearchTermVo;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SearchTermServiceImpl implements SearchTermService {

    private final SearchTermMapper searchTermMapper;
    private final SearchTermHarvestService searchTermHarvestService;
    private final DataScopeService dataScopeService;

    /** Store scope target — search_terms carries no owner column (Req 24.1). */
    private static final ScopeTarget SEARCH_TERM_SCOPE = ScopeTarget.store("store_id");

    /** Current authenticated user, or {@code null} for system/background contexts. */
    private static CurrentUser scopeUser() {
        return SecurityUtils.isAuthenticated() ? SecurityUtils.getCurrentUser() : null;
    }

    @Override
    public PageResponse<SearchTermVo> listSearchTerms(String storeId, String campaignId, String harvested, int page, int pageSize) {
        Page<SearchTermEntity> pageParam = new Page<>(page, pageSize);
        QueryWrapper<SearchTermEntity> wrapper = new QueryWrapper<>();

        if (storeId != null && !storeId.isBlank()) {
            wrapper.eq("store_id", UUID.fromString(storeId));
        }
        if (campaignId != null && !campaignId.isBlank()) {
            wrapper.eq("campaign_id", UUID.fromString(campaignId));
        }
        if ("true".equals(harvested)) {
            wrapper.eq("harvested", true);
        } else if ("false".equals(harvested)) {
            wrapper.eq("harvested", false);
        }

        // Constrain the result set to the caller's effective data scope (Req 24.1).
        CurrentUser user = scopeUser();
        if (user != null) {
            dataScopeService.applyScope(wrapper, SEARCH_TERM_SCOPE, user);
        }
        wrapper.orderByDesc("clicks");

        Page<SearchTermEntity> result = searchTermMapper.selectPage(pageParam, wrapper);
        List<SearchTermVo> voList = result.getRecords().stream()
                .map(SearchTermConverter::toVo)
                .collect(Collectors.toList());

        return PageResponse.of(voList, result.getTotal(), page, pageSize);
    }

    @Override
    public SearchTermVo harvestSearchTerm(String id, SearchTermHarvestRequest request, String userId) {
        // Per-record ownership: confirm the referenced Search_Term belongs to a Store
        // within the caller's effective data scope before harvesting it (Req 25.1/25.2).
        CurrentUser user = scopeUser();
        if (user != null) {
            SearchTermEntity entity = searchTermMapper.selectById(UUID.fromString(id));
            if (entity != null) {
                dataScopeService.assertCanWrite(entity, user);
            }
        }
        return searchTermHarvestService.harvest(id, request, userId);
    }
}
