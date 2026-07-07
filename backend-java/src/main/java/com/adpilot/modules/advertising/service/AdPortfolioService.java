package com.adpilot.modules.advertising.service;

import com.adpilot.modules.advertising.dto.AdPortfolioCreateRequest;
import com.adpilot.modules.advertising.dto.AdPortfolioUpdateRequest;
import com.adpilot.modules.advertising.vo.AdPortfolioVo;

import java.util.List;

/**
 * Ad Portfolio management (Req 20). List/create/update portfolios scoped to the
 * active store, with rollup metrics aggregated across member campaigns.
 */
public interface AdPortfolioService {

    /** List portfolios for a store with member-campaign rollup metrics (Req 20.1). */
    List<AdPortfolioVo> listPortfolios(String storeId);

    /** Get one portfolio by id with its rollup metrics. */
    AdPortfolioVo getPortfolioById(String id);

    /** Create a portfolio (Req 20.2). */
    AdPortfolioVo createPortfolio(AdPortfolioCreateRequest request, String userId);

    /** Update a portfolio (Req 20). */
    AdPortfolioVo updatePortfolio(String id, AdPortfolioUpdateRequest request, String userId);
}
