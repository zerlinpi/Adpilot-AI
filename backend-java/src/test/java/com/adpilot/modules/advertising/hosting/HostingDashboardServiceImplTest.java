package com.adpilot.modules.advertising.hosting;

import com.adpilot.modules.advertising.entity.CampaignEntity;
import com.adpilot.modules.advertising.entity.OperationEntity;
import com.adpilot.modules.advertising.mapper.CampaignMapper;
import com.adpilot.modules.advertising.mapper.OperationMapper;
import com.adpilot.modules.advertising.vo.HostingAnalyticsVo;
import com.adpilot.modules.advertising.vo.HostingDashboardSummaryVo;
import com.adpilot.modules.advertising.vo.HostingDecisionDetailVo;
import com.adpilot.modules.advertising.vo.HostingDecisionVo;
import com.adpilot.modules.advertising.vo.HostingHealthVo;
import com.adpilot.modules.apisync.entity.PlatformConnectionEntity;
import com.adpilot.modules.apisync.mapper.PlatformConnectionMapper;
import com.adpilot.modules.feishu.entity.FeishuIntegrationEntity;
import com.adpilot.modules.feishu.mapper.FeishuIntegrationMapper;
import com.adpilot.modules.inventory.entity.InventorySnapshotEntity;
import com.adpilot.modules.inventory.mapper.InventorySnapshotMapper;
import com.adpilot.modules.store.entity.MarketplaceEntity;
import com.adpilot.modules.store.entity.StoreEntity;
import com.adpilot.modules.store.mapper.MarketplaceMapper;
import com.adpilot.modules.store.mapper.StoreMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.RedisTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link HostingDashboardServiceImpl} (Req 11, 27, 29, 30.5).
 *
 * <p>Caching is disabled so the summary is computed directly from the mocked mappers.</p>
 */
@DisplayName("HostingDashboardServiceImpl")
class HostingDashboardServiceImplTest {

    static {
        // The dashboard service builds MyBatis-Plus LambdaQueryWrappers whose lambda
        // column resolution needs entity TableInfo that is normally populated during
        // Spring mapper scanning. When this test runs inside the full suite the metadata
        // may not yet be initialized, so register the entities once here so the wrappers
        // resolve in isolation. (Mirrors the pattern used across the other standalone tests.)
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, CampaignEntity.class);
        TableInfoHelper.initTableInfo(assistant, AiDecisionEntity.class);
        TableInfoHelper.initTableInfo(assistant, OperationEntity.class);
        TableInfoHelper.initTableInfo(assistant, EffectAttributionEntity.class);
        TableInfoHelper.initTableInfo(assistant, PlatformConnectionEntity.class);
        TableInfoHelper.initTableInfo(assistant, InventorySnapshotEntity.class);
        TableInfoHelper.initTableInfo(assistant, FeishuIntegrationEntity.class);
    }

    private CampaignMapper campaignMapper;
    private AiDecisionMapper aiDecisionMapper;
    private OperationMapper operationMapper;
    private EffectAttributionMapper effectAttributionMapper;
    private StoreMapper storeMapper;
    private MarketplaceMapper marketplaceMapper;
    private PlatformConnectionMapper platformConnectionMapper;
    private InventorySnapshotMapper inventorySnapshotMapper;
    private FeishuIntegrationMapper feishuIntegrationMapper;
    private LearningPeriodService learningPeriodService;
    private AiDecisionService aiDecisionService;
    private RedisTemplate<String, Object> redisTemplate;

    private HostingDashboardServiceImpl service;

    private UUID storeId;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        campaignMapper = mock(CampaignMapper.class);
        aiDecisionMapper = mock(AiDecisionMapper.class);
        operationMapper = mock(OperationMapper.class);
        effectAttributionMapper = mock(EffectAttributionMapper.class);
        storeMapper = mock(StoreMapper.class);
        marketplaceMapper = mock(MarketplaceMapper.class);
        platformConnectionMapper = mock(PlatformConnectionMapper.class);
        inventorySnapshotMapper = mock(InventorySnapshotMapper.class);
        feishuIntegrationMapper = mock(FeishuIntegrationMapper.class);
        learningPeriodService = mock(LearningPeriodService.class);
        aiDecisionService = mock(AiDecisionService.class);
        redisTemplate = mock(RedisTemplate.class);

        service = new HostingDashboardServiceImpl(campaignMapper, aiDecisionMapper, operationMapper,
                effectAttributionMapper, storeMapper, marketplaceMapper,
                platformConnectionMapper, inventorySnapshotMapper, feishuIntegrationMapper,
                learningPeriodService,
                aiDecisionService, new ObjectMapper(), redisTemplate,
                /* cacheEnabled */ false, /* cacheTtlSeconds */ 60);

        storeId = UUID.randomUUID();
        StoreEntity store = StoreEntity.builder()
                .id(storeId).orgId(UUID.randomUUID()).marketplaceId(UUID.randomUUID()).build();
        when(storeMapper.selectById(storeId)).thenReturn(store);
        when(marketplaceMapper.selectById(any())).thenReturn(
                MarketplaceEntity.builder().timezone("UTC").currency("USD").build());
    }

    @Test
    @DisplayName("getSummary aggregates counts, savings, and labels savings as an estimate")
    void getSummaryAggregates() {
        when(campaignMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(4L);
        when(campaignMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of());
        when(aiDecisionMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(7L);
        // awaiting_approval, effective_today, failed_today (in call order)
        when(operationMapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(5L, 3L, 2L);

        UUID op = UUID.randomUUID();
        when(effectAttributionMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                attribution(op, "acos", new BigDecimal("-0.04")),
                attribution(op, "spend", new BigDecimal("-12.50"))));

        HostingDashboardSummaryVo summary = service.getSummary(storeId);

        assertThat(summary.getStoreId()).isEqualTo(storeId.toString());
        assertThat(summary.getHostedCampaignsCount()).isEqualTo(4);
        assertThat(summary.getTodayDecisionsCount()).isEqualTo(7);
        assertThat(summary.getAwaitingApprovalCount()).isEqualTo(5);
        assertThat(summary.getEffectiveTodayCount()).isEqualTo(3);
        assertThat(summary.getFailedTodayCount()).isEqualTo(2);
        assertThat(summary.getEstimatedSavings7d()).isEqualByComparingTo("12.50");
        assertThat(summary.getEstimatedSavings30d()).isEqualByComparingTo("12.50");
        assertThat(summary.getEstimatedSavingsLabel()).isEqualTo("estimate");
        assertThat(summary.getLearningPeriodCampaignsCount()).isZero();
    }

    @Test
    @DisplayName("listDecisions maps decisions and joins the promoted operation state")
    void listDecisionsJoinsOperation() {
        UUID decisionId = UUID.randomUUID();
        UUID campaignId = UUID.randomUUID();
        UUID opId = UUID.randomUUID();

        AiDecisionEntity decision = AiDecisionEntity.builder()
                .id(decisionId).storeId(storeId).campaignId(campaignId)
                .engine("v1_bid").decisionType("bid_adjustment").executionMode("auto_execute")
                .routingOutcome("pending_operation").riskScore(new BigDecimal("0.30"))
                .promotedOperationId(opId).build();

        when(aiDecisionMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(decision));
        when(campaignMapper.selectById(campaignId)).thenReturn(
                CampaignEntity.builder().id(campaignId).name("Camp A").build());
        when(operationMapper.selectById(opId)).thenReturn(OperationEntity.builder()
                .id(opId).storeId(storeId).syncState("effective").field("bid")
                .beforeValue("{\"bid\":1.0}").afterValue("{\"bid\":1.2}").build());

        List<HostingDecisionVo> decisions = service.listDecisions(storeId, 50);

        assertThat(decisions).hasSize(1);
        HostingDecisionVo vo = decisions.get(0);
        assertThat(vo.getId()).isEqualTo(decisionId.toString());
        assertThat(vo.getCampaignName()).isEqualTo("Camp A");
        assertThat(vo.getSyncState()).isEqualTo("effective");
        assertThat(vo.getField()).isEqualTo("bid");
        assertThat(vo.getAfterValue()).isNotNull();
    }

    @Test
    @DisplayName("getDecisionDetail returns attributions for the promoted operation")
    void getDecisionDetailIncludesAttributions() {
        UUID decisionId = UUID.randomUUID();
        UUID opId = UUID.randomUUID();
        AiDecisionEntity decision = AiDecisionEntity.builder()
                .id(decisionId).storeId(storeId).engine("v2_budget").decisionType("budget_adjustment")
                .executionMode("approval_required").promotedOperationId(opId).build();

        when(aiDecisionService.deserializeSnapshot(decision)).thenReturn(
                DecisionSnapshot.builder().lookbackDays(14).personality("balanced").build());
        when(effectAttributionMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                attribution(opId, "spend", new BigDecimal("-5.00"))));

        HostingDecisionDetailVo detail = service.getDecisionDetail(decision);

        assertThat(detail.getDecision().getId()).isEqualTo(decisionId.toString());
        assertThat(detail.getSnapshot()).isNotNull();
        assertThat(detail.getSnapshot().getLookbackDays()).isEqualTo(14);
        assertThat(detail.getAttributions()).hasSize(1);
        assertThat(detail.getAttributions().get(0).getMetricType()).isEqualTo("spend");
    }

    @Test
    @DisplayName("getAnalytics computes rates and labels impact figures as estimates")
    void getAnalyticsComputesRates() {
        UUID opEffective = UUID.randomUUID();
        AiDecisionEntity d1 = AiDecisionEntity.builder()
                .id(UUID.randomUUID()).storeId(storeId).engine("v1_bid")
                .executionMode("auto_execute").riskScore(new BigDecimal("0.20"))
                .promotedOperationId(opEffective).build();
        AiDecisionEntity d2 = AiDecisionEntity.builder()
                .id(UUID.randomUUID()).storeId(storeId).engine("v3_keyword")
                .executionMode("observe_only").riskScore(new BigDecimal("0.10")).build();

        when(aiDecisionMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(d1, d2));
        when(operationMapper.selectBatchIds(any())).thenReturn(List.of(OperationEntity.builder()
                .id(opEffective).storeId(storeId).syncState("effective").build()));
        when(effectAttributionMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(
                attribution(opEffective, "acos", new BigDecimal("-0.03")),
                attribution(opEffective, "spend", new BigDecimal("-8.00")),
                attribution(opEffective, "sales", new BigDecimal("4.00"))));

        HostingAnalyticsVo analytics = service.getAnalytics(storeId, "30d");

        assertThat(analytics.getPeriod()).isEqualTo("30d");
        assertThat(analytics.getTotalDecisions()).isEqualTo(2);
        assertThat(analytics.getAutoExecutedCount()).isEqualTo(1);
        assertThat(analytics.getAttemptedCount()).isEqualTo(1);
        assertThat(analytics.getEffectiveCount()).isEqualTo(1);
        assertThat(analytics.getSuccessRate()).isEqualByComparingTo("1");
        assertThat(analytics.getTotalEstimatedSpendSaved()).isEqualByComparingTo("8.00");
        assertThat(analytics.getTotalEstimatedSalesLift()).isEqualByComparingTo("4.00");
        assertThat(analytics.getImpactLabel()).isEqualTo("estimate");
        assertThat(analytics.getPerEngine()).isNotEmpty();
    }

    @Test
    @DisplayName("getHealth reports all four dependencies with an aggregate status")
    void getHealthReportsDependencies() {
        // No connection factory -> redis probe reports unavailable, dragging overall down.
        when(redisTemplate.getConnectionFactory()).thenReturn(null);

        HostingHealthVo health = service.getHealth();

        assertThat(health.getDependencies()).containsKeys(
                "amazon_api", "inventory_service", "redis", "feishu");
        assertThat(health.getDependencies().get("redis")).isEqualTo("unavailable");
        assertThat(health.getOverall()).isEqualTo("unavailable");
        assertThat(health.getCheckedAt()).isNotNull();
    }

    private static EffectAttributionEntity attribution(UUID operationId, String metricType, BigDecimal impact) {
        EffectAttributionEntity e = new EffectAttributionEntity();
        e.setOperationId(operationId);
        e.setStoreId(UUID.randomUUID());
        e.setMetricType(metricType);
        e.setEstimatedIncrementalImpact(impact);
        e.setAttributionConfidence(new BigDecimal("0.5"));
        return e;
    }
}
