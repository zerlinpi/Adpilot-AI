package com.adpilot.modules.advertising.service;

import com.adpilot.common.api.PageResponse;
import com.adpilot.modules.advertising.dto.CampaignBulkRequest;
import com.adpilot.modules.advertising.dto.CampaignCreateRequest;
import com.adpilot.modules.advertising.dto.CampaignHostingRequest;
import com.adpilot.modules.advertising.support.CampaignFilter;
import com.adpilot.modules.advertising.vo.CampaignBulkResultVo;
import com.adpilot.modules.advertising.vo.CampaignTrendPointVo;
import com.adpilot.modules.advertising.vo.CampaignVo;
import com.adpilot.modules.tableview.dto.ExportRequest;

import java.io.Writer;
import java.math.BigDecimal;
import java.util.List;

public interface CampaignService {

    PageResponse<CampaignVo> listCampaigns(CampaignFilter filter, String goalId, int page, int pageSize);

    /**
     * Stream the full filtered, sorted campaign result set as CSV to {@code writer}
     * (Req 2.8). The CSV contains every matching row across all pages (not just the
     * current page), limited to {@link ExportRequest#getVisibleColumns()} rendered
     * in the supplied order, honoring the active filters and sort. Rows are read in
     * pages and written incrementally so the whole set is never held in memory at
     * once. Results are restricted to the requester's data scope.
     */
    void exportCsv(ExportRequest request, Writer writer);

    CampaignVo getCampaignById(String id);

    CampaignVo updateCampaign(String id, String name, String status, BigDecimal budget, String userId);

    /** Create a new campaign (Req 19.6). */
    CampaignVo createCampaign(CampaignCreateRequest request, String userId);

    /** Toggle a campaign's enable/pause state via the normalized {@code state} token (Req 19.5). */
    CampaignVo updateState(String id, String state, String userId);

    /** Apply a bulk operation to each campaign, returning a per-item result (Req 19.7). */
    List<CampaignBulkResultVo> bulkOperation(CampaignBulkRequest request, String userId);

    /** Aggregate the campaign data-trend panel from {@code performance_daily} (Req 19.2). */
    List<CampaignTrendPointVo> getTrend(String storeId, String startDate, String endDate, String granularity);

    /**
     * Place a campaign under AI_Hosting, persisting its hosting state,
     * Hosting_Goal and Target_ACoS (Req 21.1). The request's {@code targetAcos}
     * is required (Req 21.6) and validated before this method is reached.
     */
    CampaignVo assignHosting(String id, CampaignHostingRequest request, String userId);

    /**
     * Remove a campaign from AI_Hosting, persisting the un-hosted state so the
     * optimizer stops applying automatic optimizations to it (Req 21.5).
     */
    CampaignVo removeHosting(String id, String userId);
}
