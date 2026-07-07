package com.adpilot.modules.advertising.service.impl;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.adpilot.modules.advertising.entity.CampaignEntity;
import com.adpilot.modules.advertising.entity.CampaignProductLinkEntity;
import com.adpilot.modules.advertising.entity.RecommendationEntity;
import com.adpilot.modules.advertising.mapper.CampaignMapper;
import com.adpilot.modules.advertising.mapper.CampaignProductLinkMapper;
import com.adpilot.modules.advertising.mapper.KeywordMapper;
import com.adpilot.modules.advertising.mapper.RecommendationMapper;
import com.adpilot.modules.advertising.mapper.SmartDiagnosisTaskMapper;
import com.adpilot.modules.advertising.mapper.TargetMapper;
import com.adpilot.modules.advertising.service.RecommendationEngineService;
import com.adpilot.modules.advertising.vo.SmartDiagnosisTaskVo;
import com.adpilot.common.security.DataScopeService;
import com.adpilot.modules.product.entity.ProductEntity;
import com.adpilot.modules.product.mapper.ProductMapper;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Property-based test for parent-ASIN scoping in {@link SmartDiagnosisServiceImpl}.
 *
 * <p>Feature: advertising-workspace-rework, Property 43: Smart diagnosis is scoped
 * to its parent ASIN.
 *
 * <p>Validates: Requirements 46.1, 46.2.
 *
 * <p>Property 43 (transcribed from the design's Correctness Properties section):
 * <em>For any Smart_Diagnosis run for a parent ASIN, the analysis includes only
 * campaigns and records associated with that parent ASIN and excludes all records
 * outside it.</em>
 *
 * <p>The diagnosis resolves the in-scope campaigns from {@code campaign_product_links}
 * (the parent-ASIN link table) and then derives issues only from records under those
 * campaigns; recommendations whose campaign is not in the resolved set are excluded
 * (Req 46.2). The service is driven against mocked mappers so the test fully controls
 * which campaigns/records belong to the diagnosed parent ASIN and which belong to
 * other ASINs. We invoke the private {@code runDiagnosis} pipeline directly so the
 * assertions target the structured {@link SmartDiagnosisTaskVo.DiagnosisResult}.
 */
@Tag("pbt")
@Label("Feature: advertising-workspace-rework, Property 43: Smart diagnosis is scoped to its parent ASIN")
class SmartDiagnosisScopingPropertyTest {

    private static final int MIN_ITERATIONS = 200;

    private static final UUID STORE_ID = UUID.fromString("00000000-0000-0000-0000-00000000a001");
    private static final String DIAGNOSED_ASIN = "B0DIAGNOSED1";
    private static final String OTHER_ASIN = "B0OTHERASIN9";

    /** Title prefix used to recognise recommendation-derived issues in the result. */
    private static final String REC_PREFIX = "REC::";

    /**
     * Feature: advertising-workspace-rework, Property 43: Smart diagnosis is scoped to its parent ASIN.
     *
     * <p>Validates: Requirements 46.1, 46.2.
     *
     * <p>A diagnosis run over a parent ASIN must (a) aggregate only the campaigns linked
     * to that parent ASIN — never the out-of-scope campaigns — and (b) surface only
     * recommendation-derived issues whose campaign is in the in-scope set, while
     * surfacing every in-scope recommendation finding.
     */
    @Property(tries = MIN_ITERATIONS)
    @Label("Property 43: diagnosis includes only records for the diagnosed parent ASIN and excludes records for other ASINs")
    void diagnosisIsScopedToParentAsin(@ForAll("scenarios") Scenario scenario) throws Exception {
        SmartDiagnosisTaskMapper diagnosisTaskMapper = mock(SmartDiagnosisTaskMapper.class);
        CampaignMapper campaignMapper = mock(CampaignMapper.class);
        CampaignProductLinkMapper linkMapper = mock(CampaignProductLinkMapper.class);
        RecommendationMapper recommendationMapper = mock(RecommendationMapper.class);
        KeywordMapper keywordMapper = mock(KeywordMapper.class);
        TargetMapper targetMapper = mock(TargetMapper.class);
        ProductMapper productMapper = mock(ProductMapper.class);
        RecommendationEngineService engineService = mock(RecommendationEngineService.class);
        DataScopeService dataScopeService = mock(DataScopeService.class);

        // The link table answers the parent-ASIN scope query (WHERE store + parent_asin):
        // only the in-scope campaigns are linked to the diagnosed ASIN (Req 46.1).
        when(linkMapper.selectList(any())).thenReturn(scenario.links);

        // The campaign list query is scoped server-side via .in("id", scopedCampaignIds);
        // it returns only the in-scope campaigns — the out-of-scope ones are never fetched.
        when(campaignMapper.selectList(any())).thenReturn(scenario.inScopeCampaigns);

        // No keyword/target rows — keeps the diagnosis on the campaign + recommendation path.
        when(keywordMapper.selectCount(any())).thenReturn(0L);
        when(targetMapper.selectCount(any())).thenReturn(0L);

        // No product row for the ASIN (irrelevant to scoping).
        when(productMapper.selectOne(any())).thenReturn(null);

        // recommendationMapper.selectList is called three times by the pipeline:
        //   1) pre-run pending snapshot (beforeIds)  -> empty (nothing pre-existing)
        //   2) dedup seed of existing recommendations -> empty (fresh deduplicator)
        //   3) post-run pending recommendations        -> the run's generated set
        // The generated set deliberately mixes in-scope and out-of-scope campaigns so the
        // service-side scope filter (Req 46.2) is the only thing that can exclude the latter.
        List<RecommendationEntity> generated = new ArrayList<>();
        generated.addAll(scenario.inScopeRecs);
        generated.addAll(scenario.outScopeRecs);
        when(recommendationMapper.selectList(any()))
                .thenReturn(List.of(), List.of(), generated);

        SmartDiagnosisServiceImpl service = new SmartDiagnosisServiceImpl(
                diagnosisTaskMapper, campaignMapper, linkMapper, recommendationMapper,
                keywordMapper, targetMapper, productMapper, engineService, dataScopeService,
                new ObjectMapper());

        SmartDiagnosisTaskVo.DiagnosisResult result = runDiagnosis(service);

        // The diagnosis has in-scope ad structure to analyse.
        assertThat(result.getDataAvailable()).isTrue();

        // (46.1/46.2) Campaign aggregation reflects ONLY the in-scope campaigns. The
        // out-of-scope campaigns carry deliberately large, distinguishable spend; if any
        // leaked in, the count or the spend total would differ.
        double expectedSpend = scenario.inScopeCampaigns.stream()
                .map(CampaignEntity::getSpend)
                .mapToDouble(BigDecimal::doubleValue).sum();
        assertThat(result.getCampaignCount()).isEqualTo(scenario.inScopeCampaigns.size());
        assertThat(result.getTotalSpend()).isCloseTo(expectedSpend, org.assertj.core.api.Assertions.within(0.001));

        // The recommendation-derived issue titles surfaced by the diagnosis.
        Set<String> recIssueTitles = result.getIssues().stream()
                .map(SmartDiagnosisTaskVo.DiagnosisIssue::getTitle)
                .filter(t -> t != null && t.startsWith(REC_PREFIX))
                .collect(Collectors.toCollection(HashSet::new));

        Set<String> inScopeTitles = scenario.inScopeRecs.stream()
                .map(RecommendationEntity::getTitle).collect(Collectors.toCollection(HashSet::new));
        Set<String> outScopeTitles = scenario.outScopeRecs.stream()
                .map(RecommendationEntity::getTitle).collect(Collectors.toCollection(HashSet::new));

        Set<String> scopedCampaignIds = scenario.inScopeCampaigns.stream()
                .map(c -> c.getId().toString()).collect(Collectors.toCollection(HashSet::new));

        // (46.1) Every in-scope recommendation finding is included.
        assertThat(recIssueTitles).containsAll(inScopeTitles);

        // (46.2) No out-of-scope recommendation finding is included.
        assertThat(recIssueTitles.stream().anyMatch(outScopeTitles::contains)).isFalse();

        // (46.2) Every surfaced recommendation issue refers to an in-scope campaign id.
        for (String title : recIssueTitles) {
            String campaignId = title.substring(REC_PREFIX.length(), title.indexOf("::", REC_PREFIX.length()));
            assertThat(scopedCampaignIds).contains(campaignId);
        }
    }

    /** Invoke the private {@code runDiagnosis(String, UUID, String)} pipeline directly. */
    private static SmartDiagnosisTaskVo.DiagnosisResult runDiagnosis(SmartDiagnosisServiceImpl service)
            throws Exception {
        Method m = SmartDiagnosisServiceImpl.class.getDeclaredMethod(
                "runDiagnosis", String.class, UUID.class, String.class);
        m.setAccessible(true);
        return (SmartDiagnosisTaskVo.DiagnosisResult) m.invoke(
                service, STORE_ID.toString(), STORE_ID, DIAGNOSED_ASIN);
    }

    // -----------------------------------------------------------------------------------------
    // Scenario + generator
    // -----------------------------------------------------------------------------------------

    /** A diagnosis scenario: in-scope vs out-of-scope campaigns, links, and recommendations. */
    static final class Scenario {
        final List<CampaignEntity> inScopeCampaigns;
        final List<CampaignEntity> outScopeCampaigns;
        final List<CampaignProductLinkEntity> links;
        final List<RecommendationEntity> inScopeRecs;
        final List<RecommendationEntity> outScopeRecs;

        Scenario(List<CampaignEntity> inScopeCampaigns, List<CampaignEntity> outScopeCampaigns,
                 List<CampaignProductLinkEntity> links, List<RecommendationEntity> inScopeRecs,
                 List<RecommendationEntity> outScopeRecs) {
            this.inScopeCampaigns = inScopeCampaigns;
            this.outScopeCampaigns = outScopeCampaigns;
            this.links = links;
            this.inScopeRecs = inScopeRecs;
            this.outScopeRecs = outScopeRecs;
        }
    }

    @Provide
    Arbitrary<Scenario> scenarios() {
        return Combinators.combine(
                        Arbitraries.integers().between(1, 3),   // in-scope campaigns (>=1)
                        Arbitraries.integers().between(0, 3),   // out-of-scope campaigns
                        Arbitraries.integers().between(1, 4),   // in-scope recommendations (>=1)
                        Arbitraries.integers().between(0, 4),   // out-of-scope recommendations
                        Arbitraries.doubles().between(1.0, 50.0),   // in-scope spend seed
                        Arbitraries.doubles().between(1.0, 50.0))   // in-scope sales seed
                .as(this::buildScenario);
    }

    private Scenario buildScenario(int inScopeCount, int outScopeCount, int inRecCount, int outRecCount,
                                   double spendSeed, double salesSeed) {
        List<CampaignEntity> inScope = new ArrayList<>();
        List<CampaignProductLinkEntity> links = new ArrayList<>();
        for (int i = 0; i < inScopeCount; i++) {
            UUID id = UUID.randomUUID();
            inScope.add(CampaignEntity.builder()
                    .id(id).storeId(STORE_ID).name("in-" + i).status("enabled")
                    .spend(BigDecimal.valueOf(spendSeed + i).setScale(2, java.math.RoundingMode.HALF_UP))
                    .sales(BigDecimal.valueOf(salesSeed + i).setScale(2, java.math.RoundingMode.HALF_UP))
                    .build());
            links.add(CampaignProductLinkEntity.builder()
                    .id(UUID.randomUUID()).storeId(STORE_ID).campaignId(id)
                    .parentAsin(DIAGNOSED_ASIN).build());
        }

        // Out-of-scope campaigns belong to a DIFFERENT parent ASIN and carry deliberately
        // large, distinguishable spend; they are never returned by the parent-ASIN link
        // query, so they must not influence the diagnosis result.
        List<CampaignEntity> outScope = new ArrayList<>();
        for (int i = 0; i < outScopeCount; i++) {
            outScope.add(CampaignEntity.builder()
                    .id(UUID.randomUUID()).storeId(STORE_ID).name("out-" + i).status("enabled")
                    .spend(BigDecimal.valueOf(10_000 + i)).sales(BigDecimal.valueOf(10_000 + i))
                    .build());
        }

        // In-scope recommendations reference an in-scope campaign; a unique type per
        // recommendation keeps the dedup key distinct so each one surfaces as an issue.
        List<RecommendationEntity> inRecs = new ArrayList<>();
        for (int i = 0; i < inRecCount; i++) {
            UUID campaignId = inScope.get(i % inScope.size()).getId();
            inRecs.add(recommendation(campaignId, "rec_in_" + i, "in" + i));
        }

        // Out-of-scope recommendations reference an out-of-scope (or otherwise unlinked)
        // campaign id that is NOT in the parent-ASIN's scope.
        List<RecommendationEntity> outRecs = new ArrayList<>();
        for (int i = 0; i < outRecCount; i++) {
            UUID campaignId = outScope.isEmpty() ? UUID.randomUUID() : outScope.get(i % outScope.size()).getId();
            outRecs.add(recommendation(campaignId, "rec_out_" + i, "out" + i));
        }

        return new Scenario(inScope, outScope, links, inRecs, outRecs);
    }

    /** Build a campaign-targeted recommendation whose title encodes its campaign id for tracing. */
    private static RecommendationEntity recommendation(UUID campaignId, String type, String tag) {
        return RecommendationEntity.builder()
                .id(UUID.randomUUID()).storeId(STORE_ID)
                .campaignId(campaignId).keywordId(null).targetId(null)
                .type(type).priority("medium")
                .title(REC_PREFIX + campaignId + "::" + tag)
                .description("scoping probe " + tag)
                .status("pending")
                .build();
    }
}
