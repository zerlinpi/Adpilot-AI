package com.adpilot.modules.insights.service;

import com.adpilot.modules.insights.vo.AmcTemplatesVo;
import com.adpilot.modules.insights.vo.BrandMetricsVo;
import com.adpilot.modules.insights.vo.DataSourceActivationVo;
import com.adpilot.modules.insights.vo.MarketInsightsVo;
import com.adpilot.modules.insights.vo.ProductInsightsVo;
import com.adpilot.modules.insights.vo.SqpVo;

import java.time.LocalDate;

/**
 * Data Insights / SQP / AMC surfaces (Req 30).
 *
 * <p>Every query is scoped to the requesting user's accessible stores and may
 * be further narrowed by {@code storeId}. The product list reuses the project's
 * own {@code products} + {@code performance_daily} + {@code orders} data; the
 * brand-metric, market-insight, SQP, and AMC surfaces compute from stored data
 * where available and otherwise return an explicit "requires activation" /
 * empty payload rather than fabricating Amazon-side data.</p>
 */
public interface InsightsService {

    /** Product list rows + custom-report quota (Req 30.1, 30.2). */
    ProductInsightsVo getProductList(String storeId, LocalDate start, LocalDate end);

    /** Category-brand metrics, gated behind brand-analytics activation (Req 30.3). */
    BrandMetricsVo getBrandMetrics(String storeId, LocalDate start, LocalDate end);

    /** Available market-monitoring reports (Req 30.4). */
    MarketInsightsVo getMarketInsights(String storeId);

    /** Brand-versus-market search-query funnel metrics (Req 30.5). */
    SqpVo getSqp(String storeId, LocalDate start, LocalDate end);

    /** AMC analytical model library templates (Req 30.6, 30.7). */
    AmcTemplatesVo getAmcModels(String storeId);

    /** AMC audience-creation templates (Req 30.6, 30.7). */
    AmcTemplatesVo getAmcAudiences(String storeId);

    /** Activation status for a per-store data source (item 8). */
    DataSourceActivationVo getActivation(String storeId, String source);

    /** Activate a per-store data source, unlocking its surfaces (item 8). */
    DataSourceActivationVo activate(String storeId, String source);
}
