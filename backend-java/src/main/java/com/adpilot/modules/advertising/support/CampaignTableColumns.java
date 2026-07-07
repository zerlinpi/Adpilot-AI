package com.adpilot.modules.advertising.support;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.modules.advertising.vo.CampaignVo;
import com.adpilot.modules.tableview.filter.FilterFieldSpec;
import com.adpilot.modules.tableview.filter.FilterFieldType;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Column registry for the campaigns table: the single source of truth that maps
 * a logical column key to its display header, its physical database column and
 * {@link FilterFieldType} (for server-side filtering and sorting, Req 2.9), and a
 * value extractor that renders a {@link CampaignVo} cell as a string (for CSV
 * export, Req 2.8).
 *
 * <p>This bridges the campaign resource to the shared, resource-agnostic filter
 * component in {@code com.adpilot.modules.tableview.filter}: {@link #filterRegistry()}
 * produces the {@link FilterFieldSpec} registry consumed by the shared
 * {@code FilterValidator}/{@code FilterTranslator}, so the export endpoint (task
 * 16.2) and the query endpoint (task 16.1) address exactly the same fields with
 * the same types. The header + extractor metadata adds the export-only rendering
 * concern on top of that shared field model.
 */
public final class CampaignTableColumns {

    private CampaignTableColumns() {
    }

    /** One addressable campaign column. */
    public record Column(String key,
                         String header,
                         String sqlColumn,
                         FilterFieldType type,
                         Function<CampaignVo, Object> extractor) {
    }

    private static final Map<String, Column> COLUMNS = new LinkedHashMap<>();

    private static void register(String key, String header, String sqlColumn,
                                 FilterFieldType type, Function<CampaignVo, Object> extractor) {
        COLUMNS.put(key, new Column(key, header, sqlColumn, type, extractor));
    }

    static {
        register("id", "ID", "id", FilterFieldType.TEXT, CampaignVo::getId);
        register("storeId", "Store ID", "store_id", FilterFieldType.TEXT, CampaignVo::getStoreId);
        register("name", "Name", "name", FilterFieldType.TEXT, CampaignVo::getName);
        register("campaignType", "Ad Type", "campaign_type", FilterFieldType.TEXT, CampaignVo::getCampaignType);
        register("portfolio", "Portfolio", "portfolio", FilterFieldType.TEXT, CampaignVo::getPortfolio);
        register("portfolioId", "Portfolio ID", "portfolio_id", FilterFieldType.TEXT, CampaignVo::getPortfolioId);
        register("status", "Status", "status", FilterFieldType.TEXT, CampaignVo::getStatus);
        register("budget", "Budget", "budget", FilterFieldType.NUMBER, CampaignVo::getBudget);
        register("budgetType", "Budget Type", "budget_type", FilterFieldType.TEXT, CampaignVo::getBudgetType);
        register("startDate", "Start Date", "start_date", FilterFieldType.TEXT, CampaignVo::getStartDate);
        register("endDate", "End Date", "end_date", FilterFieldType.TEXT, CampaignVo::getEndDate);
        register("targetingType", "Targeting Type", "targeting_type", FilterFieldType.TEXT, CampaignVo::getTargetingType);
        register("spend", "Spend", "spend", FilterFieldType.NUMBER, CampaignVo::getSpend);
        register("sales", "Sales", "sales", FilterFieldType.NUMBER, CampaignVo::getSales);
        register("orders", "Orders", "orders", FilterFieldType.NUMBER, CampaignVo::getOrders);
        register("impressions", "Impressions", "impressions", FilterFieldType.NUMBER, CampaignVo::getImpressions);
        register("clicks", "Clicks", "clicks", FilterFieldType.NUMBER, CampaignVo::getClicks);
        register("acos", "ACoS", "acos", FilterFieldType.NUMBER, CampaignVo::getAcos);
        register("roas", "RoAS", "roas", FilterFieldType.NUMBER, CampaignVo::getRoas);
        register("conversionRate", "Conversion Rate", "conversion_rate", FilterFieldType.NUMBER,
                CampaignVo::getConversionRate);
        register("avgCpc", "Avg CPC", "avg_cpc", FilterFieldType.NUMBER, CampaignVo::getAvgCpc);
        register("adGroupCount", "Ad Groups", "ad_group_count", FilterFieldType.NUMBER, CampaignVo::getAdGroupCount);
        register("keywordCount", "Keywords", "keyword_count", FilterFieldType.NUMBER, CampaignVo::getKeywordCount);
        register("negativeKeywordCount", "Negative Keywords", "negative_keyword_count", FilterFieldType.NUMBER,
                CampaignVo::getNegativeKeywordCount);
        register("hostingEnabled", "Hosting Enabled", "hosting_enabled", FilterFieldType.BOOLEAN,
                CampaignVo::isHostingEnabled);
        register("hostingGoal", "Hosting Goal", "hosting_goal", FilterFieldType.TEXT, CampaignVo::getHostingGoal);
        register("targetAcos", "Target ACoS", "target_acos", FilterFieldType.NUMBER, CampaignVo::getTargetAcos);
        register("aiManaged", "AI Managed", "ai_managed", FilterFieldType.BOOLEAN, CampaignVo::isAiManaged);
        register("externalId", "External ID", "external_id", FilterFieldType.TEXT, CampaignVo::getExternalId);
        register("createdAt", "Created At", "created_at", FilterFieldType.DATE, CampaignVo::getCreatedAt);
        register("updatedAt", "Updated At", "updated_at", FilterFieldType.DATE, CampaignVo::getUpdatedAt);
    }

    /** Whether a logical column key is addressable on the campaigns table. */
    public static boolean has(String key) {
        return key != null && COLUMNS.containsKey(key);
    }

    /**
     * Look up a column by key, raising an {@code EXPORT_INVALID_COLUMN} domain
     * error when the key is not addressable.
     */
    public static Column require(String key) {
        Column column = COLUMNS.get(key);
        if (column == null) {
            throw new BusinessException("EXPORT_INVALID_COLUMN", "Unknown column: " + key);
        }
        return column;
    }

    /** Display header for a column key. */
    public static String header(String key) {
        return require(key).header();
    }

    /** Physical DB column for a column key (used to apply a validated sort). */
    public static String sqlColumn(String key) {
        return require(key).sqlColumn();
    }

    /**
     * Render a {@link CampaignVo} cell for the given column key as a string,
     * using empty string for {@code null} so the CSV cell is blank.
     */
    public static String cell(CampaignVo vo, String key) {
        Object value = require(key).extractor().apply(vo);
        return value == null ? "" : String.valueOf(value);
    }

    /**
     * Build the {@link FilterFieldSpec} registry the shared filter validator and
     * translator consume — one spec per addressable column, mapping the logical
     * field to its physical column and type.
     */
    public static Map<String, FilterFieldSpec> filterRegistry() {
        Map<String, FilterFieldSpec> registry = new LinkedHashMap<>();
        for (Column column : COLUMNS.values()) {
            registry.put(column.key(), FilterFieldSpec.of(column.key(), column.sqlColumn(), column.type()));
        }
        return registry;
    }
}
