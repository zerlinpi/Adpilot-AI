package com.adpilot.modules.ai.service.impl;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.advertising.entity.PerformanceDailyEntity;
import com.adpilot.modules.advertising.mapper.CampaignMapper;
import com.adpilot.modules.advertising.mapper.PerformanceDailyMapper;
import com.adpilot.modules.ai.dto.InsightQueryRequest;
import com.adpilot.modules.ai.entity.InsightAgentInsightEntity;
import com.adpilot.modules.ai.mapper.InsightAgentInsightMapper;
import com.adpilot.modules.ai.service.AiAssistService;
import com.adpilot.modules.ai.service.InsightAgentService;
import com.adpilot.modules.ai.vo.InsightResultVo;
import com.adpilot.modules.ai.vo.SavedInsightVo;
import com.adpilot.modules.advertising.entity.CampaignEntity;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Default {@link InsightAgentService}. Builds a compact summary of the store's
 * recent stored metrics (last 30 days of {@code performance_daily} plus the
 * store's campaigns) and either feeds it to the configured AI model or, when AI
 * is not enabled / unavailable, renders a deterministic summary directly.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InsightAgentServiceImpl implements InsightAgentService {

    private static final int LOOKBACK_DAYS = 30;
    private static final int MAX_RECOMMENDED_ACTIONS = 10; // Req 14.1
    private static final long AI_TIMEOUT_SECONDS = 30; // Req 14.1, 14.6
    private static final String SOURCE_AI = "ai"; // Req 14.5
    private static final String SOURCE_DEGRADED = "degraded"; // Req 14.4, 14.6, 14.7
    private static final DateTimeFormatter TS_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AiAssistService aiAssistService;
    private final PerformanceDailyMapper performanceDailyMapper;
    private final CampaignMapper campaignMapper;
    private final InsightAgentInsightMapper insightMapper;
    private final ObjectMapper objectMapper;

    @Override
    public InsightResultVo query(InsightQueryRequest request) {
        if (request == null || !StringUtils.hasText(request.getQuery())) {
            throw new BusinessException("INSIGHT_001", "A query is required");
        }
        String query = request.getQuery().trim();
        String source = StringUtils.hasText(request.getSource()) ? request.getSource().trim() : "all";
        boolean premium = Boolean.TRUE.equals(request.getPremium());
        String storeId = StringUtils.hasText(request.getStoreId()) ? request.getStoreId().trim() : null;

        MetricsSummary summary = summarizeStore(storeId);
        String userId = SecurityUtils.getCurrentUserIdOrNull();

        // Req 14.7 — AI not enabled is a degraded result, never marked AI-generated.
        if (!aiAssistService.isEnabled()) {
            return persist(degraded(query, source, premium, summary, "AI 未启用，已返回基于已存数据的摘要"),
                    storeId, userId);
        }

        // Req 14.1 — request analysis and enforce a 30s bound; on timeout/failure
        // fall back to a degraded result (Req 14.6) that retains the operator query.
        AiInsight ai;
        try {
            ai = requestAiAnalysis(query, source, premium, summary, userId, storeId);
        } catch (TimeoutException e) {
            log.warn("Insight Agent AI analysis exceeded {}s for query '{}'", AI_TIMEOUT_SECONDS, query);
            return persist(degraded(query, source, premium, summary,
                    "AI 分析在 " + AI_TIMEOUT_SECONDS + " 秒内未完成"), storeId, userId);
        } catch (Exception e) {
            log.warn("Insight Agent AI analysis failed: {}", e.getMessage());
            return persist(degraded(query, source, premium, summary,
                    "AI 分析失败：" + safeReason(e.getMessage())), storeId, userId);
        }

        // Req 14.3, 14.4 — validate AI output against the result structure; treat
        // missing/empty insights or otherwise non-conforming output as degraded.
        if (ai == null || !StringUtils.hasText(ai.insights())) {
            log.warn("Insight Agent received non-conforming AI output for query '{}'", query);
            return persist(degraded(query, source, premium, summary,
                    "AI 返回结果不符合预期结构"), storeId, userId);
        }

        // Req 14.1, 14.2 — cap recommended actions at 10; zero actions is valid
        // (the explanation lives in the insights text).
        List<String> actions = ai.recommendedActions();
        if (actions == null) {
            actions = new ArrayList<>();
        }
        if (actions.size() > MAX_RECOMMENDED_ACTIONS) {
            actions = new ArrayList<>(actions.subList(0, MAX_RECOMMENDED_ACTIONS));
        }

        InsightResultVo result = InsightResultVo.builder()
                .query(query)
                .source(source)
                .premium(premium)
                .insights(ai.insights())
                .recommendedActions(actions)
                .generatedBy(SOURCE_AI) // Req 14.5
                .degradedReason(null)
                .build();
        return persist(result, storeId, userId);
    }

    /**
     * Request structured analysis from the AI provider within a 30s bound
     * (Req 14.1). The model is instructed to return a JSON object so the output
     * can be validated against {@link InsightResultVo}'s structure (Req 14.3).
     * Returns {@code null} when the output is non-conforming; throws
     * {@link TimeoutException} when the 30s bound is exceeded (Req 14.6) and a
     * generic exception when the call fails or AI is unavailable.
     *
     * <p>Only aggregated, non-sensitive metrics and the operator's question are
     * sent to the model — no secret or credential value is ever included in the
     * prompt (Req 14.8).</p>
     */
    private AiInsight requestAiAnalysis(String query, String source, boolean premium,
                                        MetricsSummary summary, String userId, String storeId)
            throws Exception {
        String systemPrompt = "You are Insight Agent, an Amazon advertising data analyst. "
                + "Given a store metrics summary and an operator's question, produce concise insights "
                + "and a short list of recommended next actions. "
                + "Respond with ONLY a JSON object and no surrounding prose or markdown, of the exact shape: "
                + "{\"insights\": string, \"recommendedActions\": string[]}. "
                + "Provide at most " + MAX_RECOMMENDED_ACTIONS + " recommendedActions. "
                + "If the store has no data or no action is appropriate, return an empty recommendedActions "
                + "array and explain why in the insights field. "
                + (premium ? "Premium mode is on: provide deeper, more detailed analysis." : "");
        String userPrompt = "Analysis source: " + source + "\n"
                + "Store metrics summary (last " + LOOKBACK_DAYS + " days):\n" + summary.toPromptText() + "\n\n"
                + "Operator question: " + query;

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Optional<String>> future = executor.submit(() ->
                    aiAssistService.generate("insight-agent", userId, storeId, systemPrompt, userPrompt));
            Optional<String> output;
            try {
                output = future.get(AI_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (TimeoutException te) {
                future.cancel(true);
                throw te;
            }
            // AiAssistService returns empty on a failed/unavailable call.
            if (output == null || output.isEmpty()) {
                throw new IllegalStateException("AI 服务未返回结果");
            }
            return parseAiInsight(output.get());
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * Validate and parse the model's JSON output into an {@link AiInsight}
     * (Req 14.3). Returns {@code null} if the payload is not a JSON object, is
     * missing a textual {@code insights} field, or carries a non-array
     * {@code recommendedActions} — all of which are non-conforming (Req 14.4).
     */
    private AiInsight parseAiInsight(String raw) {
        String json = stripCodeFences(raw);
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node == null || !node.isObject()) {
                return null;
            }
            JsonNode insightsNode = node.get("insights");
            if (insightsNode == null || !insightsNode.isTextual() || !StringUtils.hasText(insightsNode.asText())) {
                return null;
            }
            List<String> actions = new ArrayList<>();
            JsonNode actionsNode = node.get("recommendedActions");
            if (actionsNode != null && !actionsNode.isNull()) {
                if (!actionsNode.isArray()) {
                    return null; // present but wrong type -> non-conforming
                }
                for (JsonNode a : actionsNode) {
                    if (a.isTextual() && StringUtils.hasText(a.asText())) {
                        actions.add(a.asText().trim());
                    }
                }
            }
            return new AiInsight(insightsNode.asText().trim(), actions);
        } catch (Exception e) {
            return null;
        }
    }

    /** Strip optional ```json fences a model may wrap its JSON output in. */
    private static String stripCodeFences(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.startsWith("```")) {
            int firstNewline = s.indexOf('\n');
            if (firstNewline >= 0) {
                s = s.substring(firstNewline + 1);
            }
            if (s.endsWith("```")) {
                s = s.substring(0, s.length() - 3);
            }
        }
        return s.trim();
    }

    /**
     * Build a degraded result (Req 14.4, 14.6, 14.7): deterministic data summary
     * content marked {@code degraded}, retaining the operator query and recording
     * the reason. Actions are capped at 10 to honour the same bound as AI output.
     */
    private InsightResultVo degraded(String query, String source, boolean premium,
                                     MetricsSummary summary, String reason) {
        log.info("Insight Agent degraded result for query '{}': {}", query, reason);
        List<String> actions = deterministicActions(summary, premium);
        if (actions.size() > MAX_RECOMMENDED_ACTIONS) {
            actions = new ArrayList<>(actions.subList(0, MAX_RECOMMENDED_ACTIONS));
        }
        return InsightResultVo.builder()
                .query(query)
                .source(source)
                .premium(premium)
                .insights(deterministicInsights(query, source, summary))
                .recommendedActions(actions)
                .generatedBy(SOURCE_DEGRADED)
                .degradedReason(reason)
                .build();
    }

    private static String safeReason(String message) {
        return StringUtils.hasText(message) ? message : "未知错误";
    }

    /** Validated, structured AI output: narrative insights plus recommended actions. */
    private record AiInsight(String insights, List<String> recommendedActions) {
    }

    /**
     * Persist a generated insight (item 16) so it appears in the saved-insights
     * history and survives reloads. Persistence failures never block returning
     * the generated result to the operator.
     */
    private InsightResultVo persist(InsightResultVo result, String storeId, String userId) {
        try {
            InsightAgentInsightEntity entity = InsightAgentInsightEntity.builder()
                    .storeId(parseUuid(storeId))
                    .query(truncate(result.getQuery(), 2000))
                    .source(result.getSource())
                    .premium(result.isPremium())
                    .insights(result.getInsights())
                    .recommendedActions(writeActions(result.getRecommendedActions()))
                    .generatedBy(result.getGeneratedBy())
                    .createdBy(parseUuid(userId))
                    .build();
            insightMapper.insert(entity);
        } catch (Exception e) {
            log.warn("Failed to persist Insight Agent result: {}", e.getMessage());
        }
        return result;
    }

    @Override
    public List<SavedInsightVo> listSavedInsights(String storeId) {
        LambdaQueryWrapper<InsightAgentInsightEntity> w = new LambdaQueryWrapper<>();
        UUID storeUuid = parseUuid(storeId);
        if (storeUuid != null) {
            w.eq(InsightAgentInsightEntity::getStoreId, storeUuid);
        }
        w.orderByDesc(InsightAgentInsightEntity::getCreatedAt);
        List<InsightAgentInsightEntity> rows = insightMapper.selectList(w);
        List<SavedInsightVo> out = new ArrayList<>();
        for (InsightAgentInsightEntity e : rows) {
            out.add(SavedInsightVo.builder()
                    .id(e.getId() != null ? e.getId().toString() : null)
                    .storeId(e.getStoreId() != null ? e.getStoreId().toString() : null)
                    .query(e.getQuery())
                    .source(e.getSource())
                    .premium(Boolean.TRUE.equals(e.getPremium()))
                    .insights(e.getInsights())
                    .recommendedActions(readActions(e.getRecommendedActions()))
                    .generatedBy(e.getGeneratedBy())
                    .createdAt(e.getCreatedAt() != null ? e.getCreatedAt().format(TS_FORMAT) : null)
                    .build());
        }
        return out;
    }

    @Override
    @Transactional
    public void deleteSavedInsight(String id) {
        UUID insightId = parseUuid(id);
        if (insightId == null) {
            throw new BusinessException("INSIGHT_002", "Invalid insight id: " + id);
        }
        if (insightMapper.selectById(insightId) == null) {
            throw new BusinessException("INSIGHT_003", "Saved insight not found: " + id);
        }
        insightMapper.deleteById(insightId);
        log.info("Saved insight {} deleted", id);
    }

    private String writeActions(List<String> actions) {
        if (actions == null || actions.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(actions);
        } catch (Exception e) {
            return null;
        }
    }

    private List<String> readActions(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() > max ? s.substring(0, max) : s;
    }

    @Override
    public List<String> suggestions() {
        // Req 24.2 — fixed suggested prompts surfaced on page load.
        return List.of(
                "分析我的产品线表现",
                "对比最近30天的广告表现",
                "生成本周店铺总结",
                "诊断一个高ACoS的广告活动",
                "检查我的AI托管广告组");
    }

    // ------------------------------------------------------------------
    // Deterministic stub rendering
    // ------------------------------------------------------------------

    private String deterministicInsights(String query, String source, MetricsSummary s) {
        if (s.days == 0 && s.campaignCount == 0) {
            return "针对该店铺暂无可用的广告数据，无法生成洞察。请先同步广告数据后再试。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("基于最近 ").append(LOOKBACK_DAYS).append(" 天的店铺数据（分析来源：").append(source).append("）：\n");
        sb.append("- 广告活动数量：").append(s.campaignCount).append("\n");
        sb.append("- 总花费：").append(money(s.spend)).append("，广告销售额：").append(money(s.sales)).append("\n");
        sb.append("- 曝光：").append(s.impressions).append("，点击：").append(s.clicks)
                .append("，订单：").append(s.orders).append("\n");
        sb.append("- ACoS：").append(percent(s.acos())).append("，CTR：").append(percent(s.ctr())).append("\n");
        sb.append("\n针对您的问题「").append(query).append("」，以上为当前可用的数据概览。");
        return sb.toString();
    }

    private List<String> deterministicActions(MetricsSummary s, boolean premium) {
        List<String> actions = new ArrayList<>();
        if (s.days == 0 && s.campaignCount == 0) {
            actions.add("同步或导入广告数据，以便生成数据洞察。");
            return actions;
        }
        BigDecimal acos = s.acos();
        if (acos.compareTo(new BigDecimal("30")) > 0) {
            actions.add("ACoS 偏高（" + percent(acos) + "），建议下调高花费低转化关键词的竞价或添加否定关键词。");
        } else {
            actions.add("ACoS 处于合理区间（" + percent(acos) + "），可考虑为表现良好的广告活动适当增加预算。");
        }
        if (s.clicks > 0 && s.orders == 0) {
            actions.add("存在点击但无订单的广告活动，建议检查商品详情页与投放相关性。");
        }
        actions.add("为高 ACoS 的广告活动启用 AI 托管，按目标 ACoS 自动优化竞价。");
        if (premium) {
            actions.add("（Premium）按父 ASIN 拆分表现，定位拖累整体效率的产品线。");
            actions.add("（Premium）生成周度趋势报告并设置自动化规则模板持续监控。");
        }
        return actions;
    }

    // ------------------------------------------------------------------
    // Metrics aggregation
    // ------------------------------------------------------------------

    private MetricsSummary summarizeStore(String storeId) {
        MetricsSummary s = new MetricsSummary();
        UUID storeUuid = parseUuid(storeId);

        LambdaQueryWrapper<PerformanceDailyEntity> pw = new LambdaQueryWrapper<>();
        pw.ge(PerformanceDailyEntity::getDate, LocalDate.now().minusDays(LOOKBACK_DAYS));
        if (storeUuid != null) {
            pw.eq(PerformanceDailyEntity::getStoreId, storeUuid);
        }
        List<PerformanceDailyEntity> rows = performanceDailyMapper.selectList(pw);
        for (PerformanceDailyEntity r : rows) {
            s.days++;
            s.spend = s.spend.add(nz(r.getSpend()));
            s.sales = s.sales.add(nz(r.getSales()));
            s.impressions += r.getImpressions() != null ? r.getImpressions() : 0L;
            s.clicks += r.getClicks() != null ? r.getClicks() : 0;
            s.orders += r.getOrders() != null ? r.getOrders() : 0;
        }

        LambdaQueryWrapper<CampaignEntity> cw = new LambdaQueryWrapper<>();
        if (storeUuid != null) {
            cw.eq(CampaignEntity::getStoreId, storeUuid);
        }
        s.campaignCount = Math.toIntExact(campaignMapper.selectCount(cw));
        return s;
    }

    private UUID parseUuid(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static BigDecimal nz(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    private static String money(BigDecimal v) {
        return nz(v).setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String percent(BigDecimal v) {
        return nz(v).setScale(2, RoundingMode.HALF_UP).toPlainString() + "%";
    }

    /** Aggregated metrics used to seed both the AI prompt and the deterministic stub. */
    private static final class MetricsSummary {
        int days;
        int campaignCount;
        long impressions;
        int clicks;
        int orders;
        BigDecimal spend = BigDecimal.ZERO;
        BigDecimal sales = BigDecimal.ZERO;

        /** ACoS = spend / sales as a percentage; 0 when there are no sales. */
        BigDecimal acos() {
            if (sales.compareTo(BigDecimal.ZERO) <= 0) {
                return BigDecimal.ZERO;
            }
            return spend.multiply(BigDecimal.valueOf(100)).divide(sales, 2, RoundingMode.HALF_UP);
        }

        /** CTR = clicks / impressions as a percentage; 0 when there are no impressions. */
        BigDecimal ctr() {
            if (impressions <= 0) {
                return BigDecimal.ZERO;
            }
            return BigDecimal.valueOf(clicks).multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(impressions), 2, RoundingMode.HALF_UP);
        }

        String toPromptText() {
            return "campaigns=" + campaignCount
                    + ", spend=" + money(spend)
                    + ", adSales=" + money(sales)
                    + ", impressions=" + impressions
                    + ", clicks=" + clicks
                    + ", orders=" + orders
                    + ", acos=" + acos() + "%"
                    + ", ctr=" + ctr() + "%";
        }
    }
}
