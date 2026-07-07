package com.adpilot.modules.profit.service.impl;

import com.adpilot.modules.advertising.entity.CampaignEntity;
import com.adpilot.modules.advertising.entity.GoalEntity;
import com.adpilot.modules.advertising.entity.PerformanceDailyEntity;
import com.adpilot.modules.advertising.mapper.CampaignMapper;
import com.adpilot.modules.advertising.mapper.GoalMapper;
import com.adpilot.modules.advertising.mapper.PerformanceDailyMapper;
import com.adpilot.modules.product.entity.ProductEntity;
import com.adpilot.modules.product.mapper.ProductMapper;
import com.adpilot.modules.profit.entity.ProductProfitDailyEntity;
import com.adpilot.modules.profit.mapper.ProductProfitDailyMapper;
import com.adpilot.modules.profit.service.ProfitService;
import com.adpilot.modules.profit.vo.ProfitAttributionVo;
import com.adpilot.modules.profit.vo.ProfitDashboardVo;
import com.adpilot.modules.profit.vo.ProductProfitVo;
import com.adpilot.modules.store.entity.StoreEntity;
import com.adpilot.modules.store.mapper.StoreMapper;
import com.adpilot.modules.store.service.MarketplaceReferenceService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProfitServiceImpl implements ProfitService {

    private final ProductProfitDailyMapper profitMapper;
    private final ProductMapper productMapper;
    private final CampaignMapper campaignMapper;
    private final GoalMapper goalMapper;
    private final PerformanceDailyMapper performanceDailyMapper;
    private final StoreMapper storeMapper;
    private final MarketplaceReferenceService marketplaceReferenceService;

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    @Override
    public ProfitDashboardVo getProfitDashboard(String storeId, String startDate, String endDate) {
        UUID storeUuid = UUID.fromString(storeId);
        // Explicit caller dates are parsed as-is; only the default "today"/range is
        // computed in the store's marketplace zone (not the server JVM zone).
        ZoneId zone = (startDate == null || endDate == null) ? zoneForStore(storeUuid) : null;
        LocalDate start = startDate != null ? LocalDate.parse(startDate) : LocalDate.now(zone).minusDays(30);
        LocalDate end = endDate != null ? LocalDate.parse(endDate) : LocalDate.now(zone);

        List<ProductProfitDailyEntity> profits = queryProfits(storeUuid, start, end);

        if (profits.isEmpty()) {
            return ProfitDashboardVo.builder()
                    .totalSales(0).adSales(0).organicSales(0).adSpend(0)
                    .grossProfit(0).netProfit(0).netMargin(0).acos(0).tacos(0).roas(0)
                    .topProfitableSkus(Collections.emptyList())
                    .profitLosingSkus(Collections.emptyList())
                    .aiSummary("暂无利润数据，请先导入销售数据。")
                    .build();
        }

        // Aggregate totals
        BigDecimal totalGrossSales = sumField(profits, ProductProfitDailyEntity::getGrossSales);
        BigDecimal totalAdSales = sumField(profits, ProductProfitDailyEntity::getAdSales);
        BigDecimal totalOrganicSales = sumField(profits, ProductProfitDailyEntity::getOrganicSales);
        BigDecimal totalAdSpend = sumField(profits, ProductProfitDailyEntity::getAdSpend);
        BigDecimal totalGrossProfit = sumField(profits, ProductProfitDailyEntity::getGrossProfit);
        BigDecimal totalNetProfit = sumField(profits, ProductProfitDailyEntity::getNetProfit);

        BigDecimal netMargin = totalGrossSales.compareTo(BigDecimal.ZERO) > 0
                ? totalNetProfit.multiply(HUNDRED).divide(totalGrossSales, 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal acos = totalAdSales.compareTo(BigDecimal.ZERO) > 0
                ? totalAdSpend.multiply(HUNDRED).divide(totalAdSales, 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal tacos = totalGrossSales.compareTo(BigDecimal.ZERO) > 0
                ? totalAdSpend.multiply(HUNDRED).divide(totalGrossSales, 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal roas = totalAdSpend.compareTo(BigDecimal.ZERO) > 0
                ? totalAdSales.divide(totalAdSpend, 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        // Group by product to find top/losing SKUs
        Map<UUID, List<ProductProfitDailyEntity>> byProduct = profits.stream()
                .filter(p -> p.getProductId() != null)
                .collect(Collectors.groupingBy(ProductProfitDailyEntity::getProductId));

        List<ProductProfitVo> productVos = byProduct.entrySet().stream()
                .map(entry -> aggregateProductProfit(entry.getKey(), entry.getValue()))
                .collect(Collectors.toList());

        List<ProductProfitVo> topProfitable = productVos.stream()
                .sorted(Comparator.comparingDouble(ProductProfitVo::getNetProfit).reversed())
                .limit(10)
                .collect(Collectors.toList());

        List<ProductProfitVo> profitLosing = productVos.stream()
                .filter(p -> p.getNetProfit() < 0)
                .sorted(Comparator.comparingDouble(ProductProfitVo::getNetProfit))
                .limit(10)
                .collect(Collectors.toList());

        // Build AI summary
        String aiSummary = buildAiSummary(totalGrossSales, totalNetProfit, netMargin, acos, topProfitable.size(), profitLosing.size());

        return ProfitDashboardVo.builder()
                .totalSales(totalGrossSales.doubleValue())
                .adSales(totalAdSales.doubleValue())
                .organicSales(totalOrganicSales.doubleValue())
                .adSpend(totalAdSpend.doubleValue())
                .grossProfit(totalGrossProfit.doubleValue())
                .netProfit(totalNetProfit.doubleValue())
                .netMargin(netMargin.doubleValue())
                .acos(acos.doubleValue())
                .tacos(tacos.doubleValue())
                .roas(roas.doubleValue())
                .topProfitableSkus(topProfitable)
                .profitLosingSkus(profitLosing)
                .aiSummary(aiSummary)
                .build();
    }

    @Override
    public List<ProductProfitVo> getProductProfits(String storeId, String startDate, String endDate) {
        UUID storeUuid = UUID.fromString(storeId);
        // Explicit caller dates are parsed as-is; only the default "today"/range is
        // computed in the store's marketplace zone (not the server JVM zone).
        ZoneId zone = (startDate == null || endDate == null) ? zoneForStore(storeUuid) : null;
        LocalDate start = startDate != null ? LocalDate.parse(startDate) : LocalDate.now(zone).minusDays(30);
        LocalDate end = endDate != null ? LocalDate.parse(endDate) : LocalDate.now(zone);

        List<ProductProfitDailyEntity> profits = queryProfits(storeUuid, start, end);

        Map<UUID, List<ProductProfitDailyEntity>> byProduct = profits.stream()
                .filter(p -> p.getProductId() != null)
                .collect(Collectors.groupingBy(ProductProfitDailyEntity::getProductId));

        return byProduct.entrySet().stream()
                .map(entry -> aggregateProductProfit(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparingDouble(ProductProfitVo::getNetProfit).reversed())
                .collect(Collectors.toList());
    }

    @Override
    public List<ProfitAttributionVo> getProfitAttribution(String storeId) {
        UUID storeUuid = UUID.fromString(storeId);
        // No caller-supplied range here: default the trailing-30-day window to the
        // store's marketplace civil day rather than the server JVM zone.
        LocalDate endDate = LocalDate.now(zoneForStore(storeUuid));
        LocalDate startDate = endDate.minusDays(30);

        // Get campaigns for this store
        LambdaQueryWrapper<CampaignEntity> campaignWrapper = new LambdaQueryWrapper<>();
        campaignWrapper.eq(CampaignEntity::getStoreId, storeUuid);
        List<CampaignEntity> campaigns = campaignMapper.selectList(campaignWrapper);

        if (campaigns.isEmpty()) {
            return Collections.emptyList();
        }

        // Get goals for lookup
        Set<UUID> goalIds = campaigns.stream()
                .map(CampaignEntity::getGoalId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, GoalEntity> goalMap = new HashMap<>();
        if (!goalIds.isEmpty()) {
            LambdaQueryWrapper<GoalEntity> goalWrapper = new LambdaQueryWrapper<>();
            goalWrapper.in(GoalEntity::getId, goalIds);
            goalMapper.selectList(goalWrapper).forEach(g -> goalMap.put(g.getId(), g));
        }

        // Get performance daily for campaigns
        Set<UUID> campaignIds = campaigns.stream().map(CampaignEntity::getId).collect(Collectors.toSet());
        LambdaQueryWrapper<PerformanceDailyEntity> perfWrapper = new LambdaQueryWrapper<>();
        perfWrapper.in(PerformanceDailyEntity::getEntityId, campaignIds)
                .eq(PerformanceDailyEntity::getEntityType, "campaign")
                .between(PerformanceDailyEntity::getDate, startDate, endDate);
        List<PerformanceDailyEntity> perfs = performanceDailyMapper.selectList(perfWrapper);

        // Group performance by campaign
        Map<UUID, List<PerformanceDailyEntity>> perfByCampaign = perfs.stream()
                .collect(Collectors.groupingBy(PerformanceDailyEntity::getEntityId));

        // Get profit data aggregated by product
        List<ProductProfitDailyEntity> profits = queryProfits(storeUuid, startDate, endDate);
        Map<UUID, BigDecimal> profitByProduct = profits.stream()
                .filter(p -> p.getProductId() != null)
                .collect(Collectors.groupingBy(
                        ProductProfitDailyEntity::getProductId,
                        Collectors.reducing(BigDecimal.ZERO, ProductProfitDailyEntity::getNetProfit, BigDecimal::add)));

        List<ProfitAttributionVo> result = new ArrayList<>();
        for (CampaignEntity campaign : campaigns) {
            List<PerformanceDailyEntity> campaignPerfs = perfByCampaign.getOrDefault(campaign.getId(), Collections.emptyList());

            BigDecimal adSpend = campaignPerfs.stream()
                    .map(PerformanceDailyEntity::getSpend)
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal adSales = campaignPerfs.stream()
                    .map(PerformanceDailyEntity::getSales)
                    .filter(Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            BigDecimal totalSales = adSales; // Ad sales from campaign perspective
            BigDecimal acos = adSales.compareTo(BigDecimal.ZERO) > 0
                    ? adSpend.multiply(HUNDRED).divide(adSales, 2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            BigDecimal tacos = totalSales.compareTo(BigDecimal.ZERO) > 0
                    ? adSpend.multiply(HUNDRED).divide(totalSales, 2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;

            BigDecimal profitAfterAds = adSales.subtract(adSpend);

            GoalEntity goal = campaign.getGoalId() != null ? goalMap.get(campaign.getGoalId()) : null;
            BigDecimal breakEvenAcos = goal != null && goal.getTargetAcos() != null
                    ? goal.getTargetAcos() : BigDecimal.ZERO;

            String aiRecommendation = buildAttributionRecommendation(acos, breakEvenAcos, profitAfterAds);

            result.add(ProfitAttributionVo.builder()
                    .campaignId(campaign.getId().toString())
                    .campaignName(campaign.getName())
                    .goalName(goal != null ? goal.getName() : null)
                    .sku(null) // Campaign-level attribution, SKU resolved via products
                    .asin(null)
                    .adSpend(adSpend.doubleValue())
                    .adSales(adSales.doubleValue())
                    .organicSales(0) // Not directly available at campaign level
                    .totalSales(totalSales.doubleValue())
                    .grossProfit(profitAfterAds.doubleValue())
                    .netProfit(profitAfterAds.doubleValue())
                    .acos(acos.doubleValue())
                    .tacos(tacos.doubleValue())
                    .breakEvenAcos(breakEvenAcos.doubleValue())
                    .profitAfterAds(profitAfterAds.doubleValue())
                    .aiRecommendation(aiRecommendation)
                    .build());
        }

        return result;
    }

    // ---- Private helpers ----

    /**
     * Resolve the civil-day timezone for a store via its marketplace. Falls back to
     * UTC (never the JVM default) when the store is unknown or has no marketplace.
     */
    private ZoneId zoneForStore(UUID storeId) {
        UUID marketplaceId = null;
        if (storeId != null) {
            StoreEntity store = storeMapper.selectById(storeId);
            if (store != null) {
                marketplaceId = store.getMarketplaceId();
            }
        }
        return marketplaceReferenceService.timezoneForMarketplace(marketplaceId);
    }

    private List<ProductProfitDailyEntity> queryProfits(UUID storeId, LocalDate start, LocalDate end) {
        LambdaQueryWrapper<ProductProfitDailyEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ProductProfitDailyEntity::getStoreId, storeId)
                .between(ProductProfitDailyEntity::getDate, start, end);
        return profitMapper.selectList(wrapper);
    }

    private ProductProfitVo aggregateProductProfit(UUID productId, List<ProductProfitDailyEntity> records) {
        // Look up product info
        ProductEntity product = productMapper.selectById(productId);
        String sku = product != null ? product.getSku() : (records.isEmpty() ? null : records.get(0).getSku());
        String asin = product != null ? product.getAsin() : (records.isEmpty() ? null : records.get(0).getAsin());
        String productName = product != null ? product.getName() : null;

        BigDecimal grossSales = sumField(records, ProductProfitDailyEntity::getGrossSales);
        BigDecimal organicSales = sumField(records, ProductProfitDailyEntity::getOrganicSales);
        BigDecimal adSales = sumField(records, ProductProfitDailyEntity::getAdSales);
        BigDecimal adSpend = sumField(records, ProductProfitDailyEntity::getAdSpend);
        BigDecimal amazonFees = sumField(records, ProductProfitDailyEntity::getAmazonReferralFee);
        BigDecimal fbaFees = sumField(records, ProductProfitDailyEntity::getFbaFee);
        BigDecimal cogs = sumField(records, ProductProfitDailyEntity::getCogs);
        BigDecimal inboundCost = sumField(records, ProductProfitDailyEntity::getInboundShippingCost);
        BigDecimal refundCost = sumField(records, ProductProfitDailyEntity::getRefundCost);
        BigDecimal grossProfit = sumField(records, ProductProfitDailyEntity::getGrossProfit);
        BigDecimal netProfit = sumField(records, ProductProfitDailyEntity::getNetProfit);

        int unitsSold = records.stream()
                .map(ProductProfitDailyEntity::getUnitsSold)
                .filter(Objects::nonNull)
                .mapToInt(Integer::intValue)
                .sum();

        BigDecimal netMargin = grossSales.compareTo(BigDecimal.ZERO) > 0
                ? netProfit.multiply(HUNDRED).divide(grossSales, 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal acos = adSales.compareTo(BigDecimal.ZERO) > 0
                ? adSpend.multiply(HUNDRED).divide(adSales, 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal tacos = grossSales.compareTo(BigDecimal.ZERO) > 0
                ? adSpend.multiply(HUNDRED).divide(grossSales, 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal breakEvenAcos = grossSales.compareTo(BigDecimal.ZERO) > 0
                ? grossProfit.multiply(HUNDRED).divide(grossSales, 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        return ProductProfitVo.builder()
                .id(productId.toString())
                .sku(sku)
                .asin(asin)
                .productName(productName)
                .unitsSold(unitsSold)
                .grossSales(grossSales.doubleValue())
                .organicSales(organicSales.doubleValue())
                .adSales(adSales.doubleValue())
                .adSpend(adSpend.doubleValue())
                .amazonFees(amazonFees.doubleValue())
                .fbaFees(fbaFees.doubleValue())
                .cogs(cogs.doubleValue())
                .inboundCost(inboundCost.doubleValue())
                .refundCost(refundCost.doubleValue())
                .grossProfit(grossProfit.doubleValue())
                .netProfit(netProfit.doubleValue())
                .netMargin(netMargin.doubleValue())
                .acos(acos.doubleValue())
                .tacos(tacos.doubleValue())
                .breakEvenAcos(breakEvenAcos.doubleValue())
                .build();
    }

    private BigDecimal sumField(List<ProductProfitDailyEntity> list,
                                java.util.function.Function<ProductProfitDailyEntity, BigDecimal> getter) {
        return list.stream()
                .map(getter)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private String buildAiSummary(BigDecimal totalSales, BigDecimal netProfit, BigDecimal netMargin,
                                   BigDecimal acos, int topCount, int losingCount) {
        StringBuilder sb = new StringBuilder();
        sb.append("本期总销售额 $").append(totalSales.setScale(2, RoundingMode.HALF_UP)).append("，");
        sb.append("净利润 $").append(netProfit.setScale(2, RoundingMode.HALF_UP)).append("，");
        sb.append("净利率 ").append(netMargin.setScale(1, RoundingMode.HALF_UP)).append("%。");
        sb.append("ACOS 为 ").append(acos.setScale(1, RoundingMode.HALF_UP)).append("%。");

        if (losingCount > 0) {
            sb.append("有 ").append(losingCount).append(" 个SKU处于亏损状态，建议检查广告投放效率和成本结构。");
        }
        if (topCount > 0) {
            sb.append("盈利最好的 ").append(topCount).append(" 个SKU贡献了主要利润。");
        }
        return sb.toString();
    }

    private String buildAttributionRecommendation(BigDecimal acos, BigDecimal breakEvenAcos, BigDecimal profitAfterAds) {
        if (profitAfterAds.compareTo(BigDecimal.ZERO) < 0) {
            return "该广告活动广告支出超过广告收入，建议降低竞价或暂停低效关键词。";
        }
        if (breakEvenAcos.compareTo(BigDecimal.ZERO) > 0 && acos.compareTo(breakEvenAcos) > 0) {
            return "ACOS超过盈亏平衡点，建议优化关键词和出价策略以提高广告效率。";
        }
        return "广告活动表现良好，利润为正，可考虑适当增加预算扩大规模。";
    }
}
