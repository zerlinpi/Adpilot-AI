package com.adpilot.modules.advertising.service.impl;

import com.adpilot.common.security.CurrentUser;
import com.adpilot.common.security.DataScopeService;
import com.adpilot.common.security.ScopeTarget;
import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.advertising.entity.CampaignEntity;
import com.adpilot.modules.advertising.entity.CampaignProductLinkEntity;
import com.adpilot.modules.advertising.mapper.AdvertisedProductReportMapper;
import com.adpilot.modules.advertising.mapper.CampaignMapper;
import com.adpilot.modules.advertising.mapper.CampaignProductLinkMapper;
import com.adpilot.modules.advertising.mapper.PerformanceDailyMapper;
import com.adpilot.modules.advertising.service.ProductAdService;
import com.adpilot.modules.advertising.support.CampaignPerfAggRow;
import com.adpilot.modules.advertising.support.ProductAdAggRow;
import com.adpilot.modules.advertising.support.StoreScopeRef;
import com.adpilot.modules.advertising.vo.ProductAdVo;
import com.adpilot.modules.advertising.vo.ProductCampaignVo;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductAdServiceImpl implements ProductAdService {

    private static final int MAX_ROWS = 500;

    /**
     * Store-scope target for product-ad list queries. The queried rows expose the
     * owning store via their {@code store_id} column, so the shared data-scope layer
     * can inject a Store_Group_Scope predicate that confines non-super-admins to
     * their own stores (Req 2.7).
     */
    private static final ScopeTarget STORE_SCOPE = ScopeTarget.store("store_id");

    private final AdvertisedProductReportMapper advertisedProductReportMapper;
    private final CampaignMapper campaignMapper;
    private final CampaignProductLinkMapper campaignProductLinkMapper;
    private final PerformanceDailyMapper performanceDailyMapper;
    private final DataScopeService dataScopeService;

    /** Current authenticated user, or {@code null} for system/background contexts. */
    private static CurrentUser scopeUser() {
        return SecurityUtils.isAuthenticated() ? SecurityUtils.getCurrentUser() : null;
    }

    @Override
    public List<ProductAdVo> listPromotedProducts(String storeId, String campaignId) {
        return aggregate(storeId, campaignId, false);
    }

    @Override
    public List<ProductAdVo> listPurchasedProducts(String storeId, String campaignId) {
        return aggregate(storeId, campaignId, true);
    }

    @Override
    public List<ProductCampaignVo> listProductCampaigns(String storeId, String parentAsin, String productId) {
        if (storeId == null || storeId.isBlank()) {
            return Collections.emptyList();
        }
        String asin = (parentAsin != null && !parentAsin.isBlank()) ? parentAsin.trim() : null;
        String prodId = (productId != null && !productId.isBlank()) ? productId.trim() : null;
        if (asin == null && prodId == null) {
            // No product selector — nothing to associate.
            return Collections.emptyList();
        }

        UUID storeUuid = UUID.fromString(storeId);

        // Product-ad data is keyed by Store; reject when the caller does not own
        // the requested Store (Req 2.7) without disclosing contents.
        CurrentUser user = scopeUser();
        if (user != null) {
            dataScopeService.assertCanRead(StoreScopeRef.of(storeUuid), user);
        }

        // Resolve product↔campaign associations via campaign_product_links by
        // parent ASIN and/or local product id (Req 2.1). The link row carries the
        // campaign's resolved parentAsin / productId.
        QueryWrapper<CampaignProductLinkEntity> linkWrapper = new QueryWrapper<>();
        linkWrapper.eq("store_id", storeUuid);
        linkWrapper.and(w -> {
            boolean needOr = false;
            if (asin != null) {
                w.eq("parent_asin", asin);
                needOr = true;
            }
            if (prodId != null) {
                if (needOr) {
                    w.or();
                }
                w.eq("product_id", UUID.fromString(prodId));
            }
        });
        // Defense-in-depth: inject the account's Store_Group_Scope predicate onto the
        // query itself so a non-super-admin only ever sees rows for stores within their
        // scope, in addition to the single-store assertCanRead guard above (Req 2.7).
        if (user != null) {
            dataScopeService.applyScope(linkWrapper, STORE_SCOPE, user);
        }
        List<CampaignProductLinkEntity> links = campaignProductLinkMapper.selectList(linkWrapper);
        if (links.isEmpty()) {
            return Collections.emptyList();
        }

        // De-duplicate by campaign id, keeping the first-seen link (its parentAsin /
        // productId describe how the campaign is associated to this product).
        Map<UUID, CampaignProductLinkEntity> linkByCampaign = new LinkedHashMap<>();
        for (CampaignProductLinkEntity link : links) {
            if (link.getCampaignId() != null) {
                linkByCampaign.putIfAbsent(link.getCampaignId(), link);
            }
        }
        if (linkByCampaign.isEmpty()) {
            return Collections.emptyList();
        }

        // Load the associated campaigns (defensively confined to this store).
        List<UUID> campaignIds = new ArrayList<>(linkByCampaign.keySet());
        Map<UUID, CampaignEntity> campaignById = new LinkedHashMap<>();
        for (CampaignEntity campaign : campaignMapper.selectBatchIds(campaignIds)) {
            if (campaign != null && storeUuid.equals(campaign.getStoreId())) {
                campaignById.put(campaign.getId(), campaign);
            }
        }
        if (campaignById.isEmpty()) {
            return Collections.emptyList();
        }

        // Aggregate performance_daily for the associated campaigns (Req 2.2) and
        // derive the faithful data_status pass-through (Req 2.3).
        List<String> campaignIdStrings = campaignById.keySet().stream()
                .map(UUID::toString)
                .collect(Collectors.toList());
        Map<String, CampaignPerfAggRow> perfByCampaign = performanceDailyMapper
                .aggregateByCampaign(storeId, campaignIdStrings)
                .stream()
                .filter(row -> row.getCampaignId() != null)
                .collect(Collectors.toMap(CampaignPerfAggRow::getCampaignId, row -> row, (a, b) -> a));

        List<ProductCampaignVo> result = new ArrayList<>();
        for (Map.Entry<UUID, CampaignEntity> entry : campaignById.entrySet()) {
            CampaignEntity campaign = entry.getValue();
            CampaignProductLinkEntity link = linkByCampaign.get(entry.getKey());
            CampaignPerfAggRow perf = perfByCampaign.get(entry.getKey().toString());
            result.add(toProductCampaignVo(campaign, link, perf));
        }
        return result;
    }

    private ProductCampaignVo toProductCampaignVo(CampaignEntity campaign,
                                                  CampaignProductLinkEntity link,
                                                  CampaignPerfAggRow perf) {
        long clicks = 0L;
        long orders = 0L;
        double spend = 0;
        double sales = 0;
        String dataStatus = null;
        if (perf != null && perf.getRowCount() != null && perf.getRowCount() > 0) {
            clicks = perf.getClicks() != null ? perf.getClicks() : 0L;
            orders = perf.getOrders() != null ? perf.getOrders() : 0L;
            spend = perf.getSpend() != null ? perf.getSpend().doubleValue() : 0;
            sales = perf.getSales() != null ? perf.getSales().doubleValue() : 0;
            // A single preliminary day keeps the whole campaign preliminary (Req 2.3).
            dataStatus = (perf.getAllFinalized() != null && perf.getAllFinalized() == 1)
                    ? "finalized" : "preliminary";
        }
        double acos = sales > 0 ? (spend / sales) * 100 : 0;

        String linkedAsin = link != null ? link.getParentAsin() : null;
        String linkedProductId = (link != null && link.getProductId() != null)
                ? link.getProductId().toString() : null;

        return ProductCampaignVo.builder()
                .campaignId(campaign.getId() != null ? campaign.getId().toString() : null)
                .campaignName(campaign.getName())
                .parentAsin(linkedAsin)
                .productId(linkedProductId)
                .status(campaign.getStatus())
                .hostingEnabled(Boolean.TRUE.equals(campaign.getHostingEnabled()))
                .spend(spend)
                .clicks((int) clicks)
                .orders((int) orders)
                .sales(sales)
                .acos(acos)
                .dataStatus(dataStatus)
                .build();
    }

    private List<ProductAdVo> aggregate(String storeId, String campaignId, boolean purchasedOnly) {
        if (storeId == null || storeId.isBlank()) {
            return Collections.emptyList();
        }

        // Product-ad aggregates are keyed by Store; reject when the caller does not
        // own the requested Store (Req 24.1, 25.3) without disclosing contents. These
        // aggregates run through a raw store-keyed mapper query (not a QueryWrapper),
        // so the single-store assertCanRead guard is the scope enforcement point for
        // this path; there is no cross-store list path to inject applyScope into (Req 2.7).
        CurrentUser user = scopeUser();
        if (user != null) {
            dataScopeService.assertCanRead(StoreScopeRef.of(UUID.fromString(storeId)), user);
        }

        // The report table keys campaigns by name; resolve the id to a name when
        // a campaign filter is supplied.
        String campaignName = null;
        if (campaignId != null && !campaignId.isBlank()) {
            CampaignEntity campaign = campaignMapper.selectById(UUID.fromString(campaignId));
            if (campaign == null) {
                return Collections.emptyList();
            }
            campaignName = campaign.getName();
        }

        List<ProductAdAggRow> rows = advertisedProductReportMapper.aggregate(storeId, campaignName, purchasedOnly, MAX_ROWS);
        return rows.stream().map(this::toVo).collect(Collectors.toList());
    }

    private ProductAdVo toVo(ProductAdAggRow row) {
        long impressions = row.getImpressions() != null ? row.getImpressions() : 0L;
        long clicks = row.getClicks() != null ? row.getClicks() : 0L;
        long orders = row.getOrders() != null ? row.getOrders() : 0L;
        double spend = row.getSpend() != null ? row.getSpend().doubleValue() : 0;
        double sales = row.getSales() != null ? row.getSales().doubleValue() : 0;
        double acos = sales > 0 ? (spend / sales) * 100 : 0;
        double ctr = impressions > 0 ? ((double) clicks / impressions) * 100 : 0;
        double cvr = clicks > 0 ? ((double) orders / clicks) * 100 : 0;
        return ProductAdVo.builder()
                .asin(row.getAsin())
                .sku(row.getSku())
                .campaignName(row.getCampaignName())
                .adGroupName(row.getAdGroupName())
                .impressions(impressions)
                .clicks((int) clicks)
                .spend(spend)
                .sales(sales)
                .orders((int) orders)
                .acos(acos)
                .ctr(ctr)
                .cvr(cvr)
                .build();
    }
}
