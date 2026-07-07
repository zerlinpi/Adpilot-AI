package com.adpilot.modules.dashboard.service.impl;

import com.adpilot.common.security.CurrentUser;
import com.adpilot.common.security.DataScopeService;
import com.adpilot.common.security.ScopeTarget;
import com.adpilot.modules.dashboard.service.AggregationService;
import com.adpilot.modules.dashboard.vo.AggregatedMetricsVo;
import com.adpilot.modules.dashboard.vo.CurrencySubtotalVo;
import com.adpilot.modules.dashboard.vo.DateRange;
import com.adpilot.modules.dashboard.vo.MetricTotalsVo;
import com.adpilot.modules.dashboard.vo.StoreMetricsVo;
import com.adpilot.modules.finance.service.CurrencyService;
import com.adpilot.modules.finance.vo.ConvertedAmount;
import com.adpilot.modules.order.entity.OrderEntity;
import com.adpilot.modules.order.mapper.OrderMapper;
import com.adpilot.modules.store.entity.MarketplaceEntity;
import com.adpilot.modules.store.entity.StoreEntity;
import com.adpilot.modules.store.mapper.MarketplaceMapper;
import com.adpilot.modules.store.mapper.StoreMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Default {@link AggregationService}. Resolves accessible stores through the
 * shared {@link DataScopeService} query layer, sums order-derived metrics per
 * store, and normalizes currencies via {@link CurrencyService} (Req 5.1).
 *
 * <p>Conversion degrades gracefully: when no exchange rate exists for a store's
 * currency, that store's revenue is excluded from the converted totals and
 * surfaced as a per-currency subtotal flagged {@code unconverted} (Req 5.1.5).
 * Currency-independent counts (orders, units) always include every accessible
 * store.</p>
 */
@Slf4j
@Service
public class AggregationServiceImpl implements AggregationService {

    /** Monetary output scale, matching the 2-dp reporting convention. */
    private static final int MONEY_SCALE = 2;

    private final DataScopeService dataScopeService;
    private final StoreMapper storeMapper;
    private final MarketplaceMapper marketplaceMapper;
    private final OrderMapper orderMapper;
    private final CurrencyService currencyService;

    /** Reporting currency all amounts are normalized to (Req 5.1.4). */
    private final String reportingCurrency;

    public AggregationServiceImpl(DataScopeService dataScopeService,
                                  StoreMapper storeMapper,
                                  MarketplaceMapper marketplaceMapper,
                                  OrderMapper orderMapper,
                                  CurrencyService currencyService,
                                  @Value("${adpilot.aggregation.reporting-currency:USD}") String reportingCurrency) {
        this.dataScopeService = dataScopeService;
        this.storeMapper = storeMapper;
        this.marketplaceMapper = marketplaceMapper;
        this.orderMapper = orderMapper;
        this.currencyService = currencyService;
        this.reportingCurrency = normalizeCurrency(reportingCurrency, "USD");
    }

    @Override
    public AggregatedMetricsVo aggregate(CurrentUser user, DateRange dateRange) {
        DateRange range = dateRange != null ? dateRange : new DateRange(null, null);

        // Req 5.1.1 / 5.1.3: only stores the user may access contribute; the shared
        // data-scope query layer excludes everything outside the user's scope.
        List<StoreEntity> stores = accessibleStores(user);
        Map<UUID, String> currencyByMarketplace = marketplaceCurrencies();

        List<StoreMetricsVo> byStore = new ArrayList<>();
        // Per-currency original subtotals, insertion-ordered for stable output.
        Map<String, BigDecimal> originalByCurrency = new LinkedHashMap<>();

        long totalOrderCount = 0L;
        long totalUnits = 0L;
        BigDecimal convertedTotal = BigDecimal.ZERO;

        // Track which currencies converted vs not, so subtotals can be flagged.
        Set<String> convertedCurrencies = new HashSet<>();
        Set<String> unconvertedCurrencies = new HashSet<>();

        for (StoreEntity store : stores) {
            List<OrderEntity> orders = ordersFor(store.getId(), range);
            StoreAccum accum = accumulate(orders);

            String currency = resolveStoreCurrency(store, currencyByMarketplace, accum.sampleCurrency);

            // Req 5.1.4: convert via CurrencyService where a rate exists.
            ConvertedAmount conv = currencyService.convert(
                    accum.revenue, currency, reportingCurrency, range.rateDate());

            BigDecimal convertedRevenue = conv.unconverted() ? null : scale(conv.converted());
            if (conv.unconverted()) {
                unconvertedCurrencies.add(currency);
            } else {
                convertedCurrencies.add(currency);
                convertedTotal = convertedTotal.add(conv.converted());
            }

            originalByCurrency.merge(currency, accum.revenue, BigDecimal::add);

            totalOrderCount += accum.orderCount;
            totalUnits += accum.unitsSold;

            byStore.add(StoreMetricsVo.builder()
                    .storeId(store.getId() != null ? store.getId().toString() : null)
                    .storeName(store.getName())
                    .currency(currency)
                    .revenue(scale(accum.revenue))
                    .convertedRevenue(convertedRevenue)
                    .unconverted(conv.unconverted())
                    .orderCount(accum.orderCount)
                    .unitsSold(accum.unitsSold)
                    .build());
        }

        List<CurrencySubtotalVo> subtotals = originalByCurrency.entrySet().stream()
                .map(e -> CurrencySubtotalVo.builder()
                        .currency(e.getKey())
                        .amount(scale(e.getValue()))
                        // A currency is unconverted only if no store with that currency
                        // could be converted (i.e. no rate exists for the pair).
                        .unconverted(unconvertedCurrencies.contains(e.getKey())
                                && !convertedCurrencies.contains(e.getKey()))
                        .build())
                .collect(Collectors.toList());

        MetricTotalsVo totals = MetricTotalsVo.builder()
                .revenue(scale(convertedTotal))
                .orderCount(totalOrderCount)
                .unitsSold(totalUnits)
                .build();

        return AggregatedMetricsVo.builder()
                .reportingCurrency(reportingCurrency)
                .totals(totals)
                .byStore(byStore)
                .currencySubtotals(subtotals)
                .build();
    }

    // ---------------------------------------------------------------------
    // store access + queries
    // ---------------------------------------------------------------------

    /**
     * Resolve the stores the user may access by applying the shared data-scope
     * filter to the {@code stores} table (Req 5.1.3). Super-admin / all-company
     * scopes add no restriction; assigned-store scopes restrict by id; other
     * scopes narrow to owned stores or deny.
     */
    private List<StoreEntity> accessibleStores(CurrentUser user) {
        QueryWrapper<StoreEntity> wrapper = new QueryWrapper<>();
        dataScopeService.applyScope(
                wrapper,
                ScopeTarget.builder().storeIdColumn("id").ownerIdColumn("created_by").build(),
                user);
        List<StoreEntity> stores = storeMapper.selectList(wrapper);
        return stores != null ? stores : List.of();
    }

    private List<OrderEntity> ordersFor(UUID storeId, DateRange range) {
        if (storeId == null) {
            return List.of();
        }
        LambdaQueryWrapper<OrderEntity> w = new LambdaQueryWrapper<>();
        w.eq(OrderEntity::getStoreId, storeId);
        if (range.start() != null) {
            w.ge(OrderEntity::getPurchaseDate, range.start().atStartOfDay());
        }
        if (range.end() != null) {
            w.le(OrderEntity::getPurchaseDate, range.end().atTime(LocalTime.MAX));
        }
        List<OrderEntity> orders = orderMapper.selectList(w);
        return orders != null ? orders : List.of();
    }

    private Map<UUID, String> marketplaceCurrencies() {
        List<MarketplaceEntity> marketplaces = marketplaceMapper.selectList(null);
        if (marketplaces == null) {
            return Map.of();
        }
        Map<UUID, String> map = new java.util.HashMap<>();
        for (MarketplaceEntity m : marketplaces) {
            if (m.getId() != null && StringUtils.hasText(m.getCurrency())) {
                map.put(m.getId(), normalizeCurrency(m.getCurrency(), reportingCurrency));
            }
        }
        return map;
    }

    // ---------------------------------------------------------------------
    // accumulation
    // ---------------------------------------------------------------------

    /** Per-store running totals derived from its orders. */
    private static final class StoreAccum {
        BigDecimal revenue = BigDecimal.ZERO;
        long orderCount = 0L;
        long unitsSold = 0L;
        String sampleCurrency;
    }

    private StoreAccum accumulate(List<OrderEntity> orders) {
        StoreAccum accum = new StoreAccum();
        Set<String> distinctOrders = new HashSet<>();
        // Monotonic per-accumulation counter used only as a last-resort fallback
        // key so two id-less/natural-key-less rows are never collapsed together.
        long fallbackSeq = 0L;
        for (OrderEntity o : orders) {
            accum.revenue = accum.revenue.add(lineRevenue(o));
            if (o.getQuantityOrdered() != null) {
                accum.unitsSold += o.getQuantityOrdered();
            }
            if (StringUtils.hasText(o.getOrderId())) {
                distinctOrders.add(o.getOrderId());
            } else {
                // No order id: fall back to a stable identifier so distinct-order
                // counting cannot collide/undercount. identityHashCode is NOT unique
                // across objects, so we key on (in order of preference) the DB row id,
                // then a stable natural key, then a monotonic per-accumulation counter.
                distinctOrders.add(fallbackOrderKey(o, fallbackSeq++));
            }
            if (accum.sampleCurrency == null && StringUtils.hasText(o.getCurrency())) {
                accum.sampleCurrency = normalizeCurrency(o.getCurrency(), null);
            }
        }
        accum.orderCount = distinctOrders.size();
        return accum;
    }

    /**
     * Stable fallback distinct-order key for an order row that has no {@code orderId}.
     * Prefers the DB row id, then a natural key composed of the row's identifying
     * fields ({@code orderItemId + sku + asin}), and only as a last resort a
     * monotonic per-accumulation sequence. Never uses {@link System#identityHashCode}
     * which is not unique across objects and can collide/undercount.
     */
    private static String fallbackOrderKey(OrderEntity o, long seq) {
        if (o.getId() != null) {
            return "id:" + o.getId();
        }
        String naturalKey = String.join("|",
                nzs(o.getOrderItemId()), nzs(o.getSku()), nzs(o.getAsin()));
        if (StringUtils.hasText(naturalKey.replace("|", ""))) {
            return "nk:" + naturalKey;
        }
        return "row:" + seq;
    }

    /** Null-safe string for natural-key composition. */
    private static String nzs(String value) {
        return value != null ? value : "";
    }

    /** Revenue for a single order line: item + shipping, net of promotions. */
    private static BigDecimal lineRevenue(OrderEntity o) {
        BigDecimal revenue = nz(o.getItemPrice())
                .add(nz(o.getShippingPrice()))
                .subtract(nz(o.getItemPromotionDiscount()))
                .subtract(nz(o.getShipPromotionDiscount()));
        return revenue;
    }

    // ---------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------

    private String resolveStoreCurrency(StoreEntity store, Map<UUID, String> byMarketplace, String sampleCurrency) {
        String fromMarketplace = store.getMarketplaceId() != null ? byMarketplace.get(store.getMarketplaceId()) : null;
        if (StringUtils.hasText(fromMarketplace)) {
            return fromMarketplace;
        }
        if (StringUtils.hasText(sampleCurrency)) {
            return sampleCurrency;
        }
        return reportingCurrency;
    }

    private static BigDecimal nz(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    private static BigDecimal scale(BigDecimal value) {
        return nz(value).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private static String normalizeCurrency(String code, String fallback) {
        if (!StringUtils.hasText(code)) {
            return fallback;
        }
        return code.trim().toUpperCase();
    }
}
