package com.adpilot.modules.advertising.service.impl;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.adpilot.modules.advertising.entity.AdGroupEntity;
import com.adpilot.modules.advertising.entity.CampaignEntity;
import com.adpilot.modules.advertising.entity.CampaignProductLinkEntity;
import com.adpilot.modules.advertising.entity.GoalEntity;
import com.adpilot.modules.advertising.entity.KeywordEntity;
import com.adpilot.modules.advertising.entity.RecommendationEntity;
import com.adpilot.modules.advertising.entity.TargetEntity;
import com.adpilot.modules.advertising.mapper.AdGroupMapper;
import com.adpilot.modules.advertising.mapper.CampaignMapper;
import com.adpilot.modules.advertising.mapper.CampaignProductLinkMapper;
import com.adpilot.modules.advertising.mapper.GoalMapper;
import com.adpilot.modules.advertising.mapper.KeywordMapper;
import com.adpilot.modules.advertising.mapper.RecommendationMapper;
import com.adpilot.modules.advertising.mapper.TargetMapper;
import com.adpilot.modules.advertising.support.AcosScale;
import com.adpilot.modules.product.entity.ProductEntity;
import com.adpilot.modules.product.mapper.ProductMapper;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Property-based test for the hardened recommendation engine
 * ({@link RecommendationEngineServiceImpl}).
 *
 * <p>Feature: advertising-workspace-rework, Property 41: Recommendation engine
 * robustness and resolution.
 *
 * <p>Validates: Requirements 18.1, 18.2, 18.3, 18.4, 18.6.
 *
 * <p>Property 41 (transcribed from the design's Correctness Properties section):
 * <em>For any record (including records with null fields, no bid, or no sales),
 * the recommendation engine completes without a null-reference error; uses the
 * Ad_Group default bid when the record has no bid and skips bid recommendations
 * when neither exists; treats zero sales under the spend-without-sales waste rule
 * rather than the ACoS-threshold rule; and resolves the target ACoS in the order
 * Goal target ACoS → product {@code target_acos} → configurable system default
 * (0.25).</em>
 *
 * <p>The engine runs against mocked mappers so the test fully controls the
 * keyword/campaign/target records (and their null fields), the Ad_Group default
 * bid, and the Goal/product target-ACoS sources. The four facets of Property 41
 * are exercised by four focused property methods, all tagged Property 41.
 */
@Tag("pbt")
@Label("Feature: advertising-workspace-rework, Property 41: Recommendation engine robustness and resolution")
class RecommendationEngineRobustnessPropertyTest {

    private static final int MIN_ITERATIONS = 200;

    private static final UUID STORE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID CAMPAIGN_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final UUID GOAL_ID = UUID.fromString("00000000-0000-0000-0000-0000000000d1");
    private static final UUID ADGROUP_ID = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
    private static final UUID PRODUCT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000f1");

    /** ACoS-threshold rule types — the rules that must NOT fire at zero sales (Req 18.4). */
    private static final List<String> ACOS_THRESHOLD_TYPES = List.of("decrease_bid", "add_exact", "increase_bid");

    // -----------------------------------------------------------------------------------------
    // Facet A — Req 18.1: completes without a null-reference error for any record.
    // -----------------------------------------------------------------------------------------

    /**
     * Feature: advertising-workspace-rework, Property 41: Recommendation engine robustness and resolution.
     *
     * <p>Validates: Requirements 18.1.
     */
    @Property(tries = MIN_ITERATIONS)
    @Label("Property 41: the engine completes without a null-reference error for records with arbitrary null fields")
    void engineIsNullSafeForArbitraryRecords(
            @ForAll("keywordLists") List<KeywordEntity> keywords,
            @ForAll("campaignLists") List<CampaignEntity> campaigns,
            @ForAll("targetLists") List<TargetEntity> targets) {

        // Always include the most hostile edge records: every metric null and even the
        // foreign keys (campaign/ad-group) null, so the null-coalescing and resolution
        // helpers are exercised on a fully empty record on every iteration.
        List<KeywordEntity> allKeywords = new ArrayList<>(keywords);
        allKeywords.add(allNullKeyword());

        RecommendationMapper recommendationMapper = mock(RecommendationMapper.class);
        KeywordMapper keywordMapper = mock(KeywordMapper.class);
        TargetMapper targetMapper = mock(TargetMapper.class);
        CampaignMapper campaignMapper = mock(CampaignMapper.class);
        GoalMapper goalMapper = mock(GoalMapper.class);
        AdGroupMapper adGroupMapper = mock(AdGroupMapper.class);
        CampaignProductLinkMapper linkMapper = mock(CampaignProductLinkMapper.class);
        ProductMapper productMapper = mock(ProductMapper.class);

        when(keywordMapper.selectList(any())).thenReturn(allKeywords);
        when(campaignMapper.selectList(any())).thenReturn(campaigns);
        when(targetMapper.selectList(any())).thenReturn(targets);
        // goal/adGroup/link/product/recommendation selectList default to null → engine treats as empty.

        RecommendationEngineServiceImpl engine = newEngine(recommendationMapper, keywordMapper, targetMapper,
                campaignMapper, goalMapper, adGroupMapper, linkMapper, productMapper);

        // The only assertion that matters here is that no exception (NPE) escapes and the
        // engine returns a non-negative count of generated Recommendations.
        int generated = engine.generateRecommendations(STORE_ID.toString());
        assertThat(generated).isGreaterThanOrEqualTo(0);
    }

    // -----------------------------------------------------------------------------------------
    // Facet B — Req 18.2 / 18.3: Ad_Group default-bid fallback, and skip when neither bid exists.
    // -----------------------------------------------------------------------------------------

    /**
     * Feature: advertising-workspace-rework, Property 41: Recommendation engine robustness and resolution.
     *
     * <p>Validates: Requirements 18.2, 18.3.
     */
    @Property(tries = MIN_ITERATIONS)
    @Label("Property 41: a bid recommendation uses the record bid, else the Ad_Group default bid, and is skipped when neither exists")
    void bidRecommendationUsesBaselineOrIsSkipped(
            @ForAll("nullableBids") BigDecimal recordBid,
            @ForAll("nullableBids") BigDecimal defaultBid) {

        RecommendationMapper recommendationMapper = mock(RecommendationMapper.class);
        KeywordMapper keywordMapper = mock(KeywordMapper.class);
        TargetMapper targetMapper = mock(TargetMapper.class);
        CampaignMapper campaignMapper = mock(CampaignMapper.class);
        GoalMapper goalMapper = mock(GoalMapper.class);
        AdGroupMapper adGroupMapper = mock(AdGroupMapper.class);
        CampaignProductLinkMapper linkMapper = mock(CampaignProductLinkMapper.class);
        ProductMapper productMapper = mock(ProductMapper.class);

        // A keyword that triggers the High-ACoS (decrease_bid) ACoS-threshold rule: it has sales,
        // spend above the $20 floor, and an ACoS well above the default target (0.25 × 1.3 = 0.325).
        // The decrease_bid rule is a *bid* recommendation, so it requires a baseline bid.
        KeywordEntity keyword = KeywordEntity.builder()
                .id(UUID.randomUUID()).storeId(STORE_ID)
                .campaignId(CAMPAIGN_ID).adGroupId(ADGROUP_ID)
                .keywordText("kw").matchType("broad").status("enabled")
                .bid(recordBid)
                .spend(new BigDecimal("50.00"))
                .sales(new BigDecimal("10.00"))
                .acos(new BigDecimal("1.00"))
                .clicks(40).orders(0)
                .cvr(BigDecimal.ZERO).impressions(100L)
                .build();
        AdGroupEntity adGroup = AdGroupEntity.builder()
                .id(ADGROUP_ID).campaignId(CAMPAIGN_ID).storeId(STORE_ID).name("group")
                .defaultBid(defaultBid).build();

        when(keywordMapper.selectList(any())).thenReturn(List.of(keyword));
        when(adGroupMapper.selectList(any())).thenReturn(List.of(adGroup));
        // No campaign/goal/product → resolves to the default target ACoS (0.25).

        RecommendationEngineServiceImpl engine = newEngine(recommendationMapper, keywordMapper, targetMapper,
                campaignMapper, goalMapper, adGroupMapper, linkMapper, productMapper);

        engine.generateRecommendations(STORE_ID.toString());

        BigDecimal expectedBaseline = expectedBaseline(recordBid, defaultBid);

        if (expectedBaseline == null) {
            // Req 18.3 — neither a record bid nor an Ad_Group default bid → no bid recommendation.
            verify(recommendationMapper, never()).insert(any());
        } else {
            // Req 18.2 — exactly one decrease_bid recommendation whose current value is the resolved
            // baseline bid (the record bid when present, otherwise the Ad_Group default bid).
            List<RecommendationEntity> inserted = captureInserts(recommendationMapper);
            assertThat(inserted).hasSize(1);
            RecommendationEntity rec = inserted.get(0);
            assertThat(rec.getType()).isEqualTo("decrease_bid");
            assertThat(rec.getCurrentValue()).isEqualTo(expectedBaseline.toPlainString());
        }
    }

    // -----------------------------------------------------------------------------------------
    // Facet C — Req 18.4: zero sales is evaluated under the waste rule, never the ACoS-threshold rule.
    // -----------------------------------------------------------------------------------------

    /**
     * Feature: advertising-workspace-rework, Property 41: Recommendation engine robustness and resolution.
     *
     * <p>Validates: Requirements 18.4.
     */
    @Property(tries = MIN_ITERATIONS)
    @Label("Property 41: a zero-sales record is evaluated under the spend-without-sales waste rule, never the ACoS-threshold rule")
    void zeroSalesUsesWasteRuleNotAcosThreshold(
            @ForAll("zeroOrNullSales") BigDecimal sales,
            @ForAll("spendValues") BigDecimal spend,
            @ForAll("clickValues") int clicks,
            @ForAll("ordersValues") int orders) {

        RecommendationMapper recommendationMapper = mock(RecommendationMapper.class);
        KeywordMapper keywordMapper = mock(KeywordMapper.class);
        TargetMapper targetMapper = mock(TargetMapper.class);
        CampaignMapper campaignMapper = mock(CampaignMapper.class);
        GoalMapper goalMapper = mock(GoalMapper.class);
        AdGroupMapper adGroupMapper = mock(AdGroupMapper.class);
        CampaignProductLinkMapper linkMapper = mock(CampaignProductLinkMapper.class);
        ProductMapper productMapper = mock(ProductMapper.class);

        // Zero sales, but a deliberately huge ACoS and >=2 orders: if the engine wrongly applied the
        // ACoS-threshold rules it would emit a decrease_bid (high ACoS) or add_exact (winner). A
        // positive bid guarantees a baseline exists, so a missing baseline can never be the reason a
        // bid recommendation is absent.
        KeywordEntity keyword = KeywordEntity.builder()
                .id(UUID.randomUUID()).storeId(STORE_ID)
                .campaignId(CAMPAIGN_ID).adGroupId(ADGROUP_ID)
                .keywordText("kw").matchType("broad").status("enabled")
                .bid(new BigDecimal("1.00"))
                .spend(spend)
                .sales(sales)
                .acos(new BigDecimal("5.00"))
                .clicks(clicks).orders(orders)
                .cvr(BigDecimal.ZERO).impressions(100L)
                .build();

        when(keywordMapper.selectList(any())).thenReturn(List.of(keyword));

        RecommendationEngineServiceImpl engine = newEngine(recommendationMapper, keywordMapper, targetMapper,
                campaignMapper, goalMapper, adGroupMapper, linkMapper, productMapper);

        engine.generateRecommendations(STORE_ID.toString());

        List<RecommendationEntity> inserted = captureInserts(recommendationMapper);

        // No ACoS-threshold-rule recommendation may be produced at zero sales (ACoS is undefined).
        assertThat(inserted)
                .extracting(RecommendationEntity::getType)
                .doesNotContainAnyElementsOf(ACOS_THRESHOLD_TYPES);

        // The waste (spend-without-sales) rule fires iff there are >=15 clicks and >= $15 spend.
        boolean wasteRuleApplies = clicks >= 15 && spend.compareTo(BigDecimal.valueOf(15)) >= 0;
        if (wasteRuleApplies) {
            assertThat(inserted).hasSize(1);
            assertThat(inserted.get(0).getType()).isEqualTo("add_negative");
        } else {
            assertThat(inserted).isEmpty();
        }
    }

    // -----------------------------------------------------------------------------------------
    // Facet D — Req 18.6: target-ACoS resolution order Goal → product → configurable default.
    // -----------------------------------------------------------------------------------------

    /** The three target-ACoS configurations, selecting which source should win resolution. */
    enum AcosSource { GOAL, PRODUCT, DEFAULT }

    /**
     * Feature: advertising-workspace-rework, Property 41: Recommendation engine robustness and resolution.
     *
     * <p>Validates: Requirements 18.6.
     */
    @Property(tries = MIN_ITERATIONS)
    @Label("Property 41: the target ACoS resolves in the order Goal target ACoS → product target_acos → configurable default")
    void targetAcosResolutionOrder(
            @ForAll AcosSource source,
            @ForAll("goalAcosValues") BigDecimal goalAcos,
            @ForAll("productAcosValues") BigDecimal productAcos) {

        BigDecimal configuredDefault = new BigDecimal("0.10");
        BigDecimal expectedRaw = switch (source) {
            case GOAL -> goalAcos;
            case PRODUCT -> productAcos;
            case DEFAULT -> configuredDefault;
        };
        BigDecimal expectedTarget = AcosScale.normalizeRatio(expectedRaw);
        BigDecimal delta = new BigDecimal("0.05");

        // The winner rule (add_exact) fires iff ACoS <= resolved target. Probing just below the
        // expected target must emit it; probing just above must not. Because the candidate target
        // values are well-separated, a wrong resolution choice flips one of these probes.
        runWinnerProbe(source, goalAcos, productAcos, configuredDefault,
                expectedTarget.subtract(delta), true);
        runWinnerProbe(source, goalAcos, productAcos, configuredDefault,
                expectedTarget.add(delta), false);
    }

    /**
     * Run the winner-rule probe: build a keyword whose only firing rule can be the winner
     * (add_exact) rule, configure the requested target-ACoS source, and assert whether an
     * add_exact recommendation is emitted for the given probe ACoS.
     */
    private void runWinnerProbe(AcosSource source, BigDecimal goalAcos, BigDecimal productAcos,
                                BigDecimal configuredDefault, BigDecimal probeAcos, boolean expectEmit) {
        RecommendationMapper recommendationMapper = mock(RecommendationMapper.class);
        KeywordMapper keywordMapper = mock(KeywordMapper.class);
        TargetMapper targetMapper = mock(TargetMapper.class);
        CampaignMapper campaignMapper = mock(CampaignMapper.class);
        GoalMapper goalMapper = mock(GoalMapper.class);
        AdGroupMapper adGroupMapper = mock(AdGroupMapper.class);
        CampaignProductLinkMapper linkMapper = mock(CampaignProductLinkMapper.class);
        ProductMapper productMapper = mock(ProductMapper.class);

        CampaignEntity campaign = CampaignEntity.builder()
                .id(CAMPAIGN_ID).storeId(STORE_ID).name("c").status("enabled")
                .goalId(source == AcosSource.GOAL ? GOAL_ID : null)
                .build();
        when(campaignMapper.selectList(any())).thenReturn(List.of(campaign));

        if (source == AcosSource.GOAL) {
            GoalEntity goal = GoalEntity.builder()
                    .id(GOAL_ID).storeId(STORE_ID).name("g").targetAcos(goalAcos).build();
            when(goalMapper.selectList(any())).thenReturn(List.of(goal));
        }
        // For GOAL and PRODUCT, wire a product link + product so the product source is available.
        // (For GOAL this proves the Goal target ACoS takes precedence over the product target ACoS.)
        if (source == AcosSource.GOAL || source == AcosSource.PRODUCT) {
            CampaignProductLinkEntity link = CampaignProductLinkEntity.builder()
                    .id(UUID.randomUUID()).storeId(STORE_ID)
                    .campaignId(CAMPAIGN_ID).productId(PRODUCT_ID).build();
            when(linkMapper.selectList(any())).thenReturn(List.of(link));
            ProductEntity product = ProductEntity.builder()
                    .id(PRODUCT_ID).storeId(STORE_ID).sku("sku").name("p").targetAcos(productAcos).build();
            when(productMapper.selectList(any())).thenReturn(List.of(product));
        }

        KeywordEntity keyword = KeywordEntity.builder()
                .id(UUID.randomUUID()).storeId(STORE_ID)
                .campaignId(CAMPAIGN_ID).adGroupId(ADGROUP_ID)
                .keywordText("kw").matchType("broad").status("enabled")
                .bid(new BigDecimal("1.00"))
                .spend(new BigDecimal("5.00"))   // <= $20 so the High-ACoS rule cannot fire
                .sales(new BigDecimal("10.00"))  // > 0 so the winner rule is eligible
                .acos(probeAcos)
                .clicks(2).orders(3)             // >= 2 orders so the winner rule is eligible
                .cvr(BigDecimal.ZERO).impressions(100L)
                .build();
        when(keywordMapper.selectList(any())).thenReturn(List.of(keyword));

        RecommendationEngineServiceImpl engine = newEngine(recommendationMapper, keywordMapper, targetMapper,
                campaignMapper, goalMapper, adGroupMapper, linkMapper, productMapper);
        setDefaultTargetAcos(engine, configuredDefault);

        engine.generateRecommendations(STORE_ID.toString());

        List<RecommendationEntity> inserted = captureInserts(recommendationMapper);
        if (expectEmit) {
            assertThat(inserted).hasSize(1);
            assertThat(inserted.get(0).getType()).isEqualTo("add_exact");
        } else {
            assertThat(inserted).isEmpty();
        }
    }

    // -----------------------------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------------------------

    private static RecommendationEngineServiceImpl newEngine(RecommendationMapper recommendationMapper,
                                                             KeywordMapper keywordMapper,
                                                             TargetMapper targetMapper,
                                                             CampaignMapper campaignMapper,
                                                             GoalMapper goalMapper,
                                                             AdGroupMapper adGroupMapper,
                                                             CampaignProductLinkMapper linkMapper,
                                                             ProductMapper productMapper) {
        return new RecommendationEngineServiceImpl(recommendationMapper, keywordMapper, targetMapper,
                campaignMapper, goalMapper, adGroupMapper, linkMapper, productMapper);
    }

    private static List<RecommendationEntity> captureInserts(RecommendationMapper mapper) {
        ArgumentCaptor<RecommendationEntity> captor = ArgumentCaptor.forClass(RecommendationEntity.class);
        verify(mapper, atLeast(0)).insert(captor.capture());
        return captor.getAllValues();
    }

    /** Mirror of the engine's baseline-bid resolution (record bid → Ad_Group default → none). */
    private static BigDecimal expectedBaseline(BigDecimal recordBid, BigDecimal defaultBid) {
        if (recordBid != null && recordBid.signum() > 0) {
            return recordBid;
        }
        if (defaultBid != null && defaultBid.signum() > 0) {
            return defaultBid;
        }
        return null;
    }

    private static void setDefaultTargetAcos(RecommendationEngineServiceImpl engine, BigDecimal value) {
        try {
            Field field = RecommendationEngineServiceImpl.class.getDeclaredField("defaultTargetAcos");
            field.setAccessible(true);
            field.set(engine, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Unable to set defaultTargetAcos", e);
        }
    }

    private static KeywordEntity allNullKeyword() {
        return KeywordEntity.builder()
                .id(UUID.randomUUID()).storeId(STORE_ID)
                .campaignId(null).adGroupId(null)
                .keywordText(null).matchType(null).status("enabled")
                .bid(null).spend(null).sales(null).acos(null)
                .clicks(null).orders(null).cvr(null).impressions(null)
                .build();
    }

    // -----------------------------------------------------------------------------------------
    // Generators
    // -----------------------------------------------------------------------------------------

    private static Arbitrary<BigDecimal> nullableDecimals() {
        return Arbitraries.doubles().between(0.0, 500.0)
                .map(d -> BigDecimal.valueOf(d).setScale(4, RoundingMode.HALF_UP))
                .injectNull(0.3);
    }

    private static Arbitrary<Integer> nullableInts() {
        return Arbitraries.integers().between(0, 100).injectNull(0.3);
    }

    private static Arbitrary<Long> nullableLongs() {
        return Arbitraries.longs().between(0L, 100_000L).injectNull(0.3);
    }

    private static Arbitrary<String> nullableStatuses() {
        return Arbitraries.of("enabled", "paused", "archived", null);
    }

    @Provide
    Arbitrary<List<KeywordEntity>> keywordLists() {
        Arbitrary<KeywordEntity> one = Combinators.combine(
                        nullableDecimals(), nullableDecimals(), nullableDecimals(), nullableDecimals(),
                        nullableInts(), nullableInts(), nullableDecimals(), nullableLongs())
                .as((bid, spend, sales, acos, clicks, orders, cvr, impressions) -> KeywordEntity.builder()
                        .id(UUID.randomUUID()).storeId(STORE_ID)
                        .campaignId(UUID.randomUUID()).adGroupId(UUID.randomUUID())
                        .keywordText(null).matchType(null).status("enabled")
                        .bid(bid).spend(spend).sales(sales).acos(acos)
                        .clicks(clicks).orders(orders).cvr(cvr).impressions(impressions)
                        .build());
        return one.list().ofMinSize(0).ofMaxSize(6);
    }

    @Provide
    Arbitrary<List<CampaignEntity>> campaignLists() {
        Arbitrary<CampaignEntity> one = Combinators.combine(
                        nullableStatuses(), nullableDecimals(), nullableDecimals(),
                        nullableDecimals(), nullableDecimals())
                .as((status, roas, budget, spend, sales) -> CampaignEntity.builder()
                        .id(UUID.randomUUID()).storeId(STORE_ID).name("c").goalId(null)
                        .status(status).roas(roas).budget(budget).spend(spend).sales(sales)
                        .build());
        return one.list().ofMinSize(0).ofMaxSize(5);
    }

    @Provide
    Arbitrary<List<TargetEntity>> targetLists() {
        Arbitrary<TargetEntity> one = Combinators.combine(
                        nullableStatuses(), nullableDecimals(), nullableDecimals())
                .as((status, spend, sales) -> TargetEntity.builder()
                        .id(UUID.randomUUID()).storeId(STORE_ID).campaignId(UUID.randomUUID())
                        .adGroupId(UUID.randomUUID()).status(status)
                        .spend(spend).sales(sales).targetingValue(null)
                        .build());
        return one.list().ofMinSize(0).ofMaxSize(5);
    }

    @Provide
    Arbitrary<BigDecimal> nullableBids() {
        Arbitrary<BigDecimal> positive = Arbitraries.doubles().between(0.05, 5.0)
                .map(d -> BigDecimal.valueOf(d).setScale(2, RoundingMode.HALF_UP));
        return Arbitraries.oneOf(positive, Arbitraries.just(BigDecimal.ZERO)).injectNull(0.3);
    }

    @Provide
    Arbitrary<BigDecimal> zeroOrNullSales() {
        return Arbitraries.just(BigDecimal.ZERO).injectNull(0.5);
    }

    @Provide
    Arbitrary<BigDecimal> spendValues() {
        return Arbitraries.doubles().between(0.0, 40.0)
                .map(d -> BigDecimal.valueOf(d).setScale(2, RoundingMode.HALF_UP));
    }

    @Provide
    Arbitrary<Integer> clickValues() {
        return Arbitraries.integers().between(0, 30);
    }

    @Provide
    Arbitrary<Integer> ordersValues() {
        return Arbitraries.integers().between(0, 5);
    }

    @Provide
    Arbitrary<BigDecimal> goalAcosValues() {
        return Arbitraries.doubles().between(0.55, 0.65)
                .map(d -> BigDecimal.valueOf(d).setScale(4, RoundingMode.HALF_UP));
    }

    @Provide
    Arbitrary<BigDecimal> productAcosValues() {
        return Arbitraries.doubles().between(0.30, 0.40)
                .map(d -> BigDecimal.valueOf(d).setScale(4, RoundingMode.HALF_UP));
    }
}
