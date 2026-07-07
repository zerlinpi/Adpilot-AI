package com.adpilot.modules.advertising.service;

import com.adpilot.modules.advertising.entity.CampaignEntity;
import com.adpilot.modules.advertising.entity.GoalEntity;
import com.adpilot.modules.advertising.mapper.CampaignMapper;
import com.adpilot.modules.advertising.support.AcosScale;
import com.adpilot.modules.advertising.support.GoalMetrics;
import com.adpilot.modules.advertising.support.GoalPropagationResult;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.mockito.invocation.InvocationOnMock;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Property-based test for Goal data correctness in {@link GoalMetricsService}.
 *
 * <p>Feature: advertising-workspace-rework, Property 47: Goal metrics are scoped and propagation
 * is whitelisted.
 *
 * <p>Validates: Requirements 20.1, 20.2, 20.3.
 *
 * <p>Two independent guarantees of Requirement 20 are exercised:
 * <ul>
 *   <li><b>Scoped metrics (Req 20.1):</b> when the service computes a Goal's metrics it queries the
 *       {@link CampaignMapper} filtered to that Goal's id and aggregates <em>only</em> the Campaigns
 *       associated with that Goal — never the whole Store. The mapper is modelled as a Goal-scoped
 *       store so out-of-Goal Campaigns (carrying deliberately huge, distinguishable spend) can never
 *       leak into the result, and the query wrapper is asserted to be scoped to the diagnosed Goal's
 *       id and not another Goal's id.</li>
 *   <li><b>Whitelisted propagation (Req 20.2 / 20.3):</b> a Goal update propagates <em>exactly</em>
 *       the target ACoS and the optimization goal onto associated Campaigns, never the Goal name,
 *       budget, budget type, or Campaign personality, and never overwrites a Campaign that already
 *       defines its own value for a propagated field (a Campaign-level override always wins).</li>
 * </ul>
 */
@Tag("pbt")
@Label("Feature: advertising-workspace-rework, Property 47: Goal metrics are scoped and propagation is whitelisted")
class GoalMetricScopingPropertyTest {

    private static final int MIN_ITERATIONS = 200;

    static {
        // Register entity metadata so MyBatis-Plus can resolve lambda columns and materialise bound
        // parameter values for query-wrapper introspection, without a running Spring/MyBatis context.
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, CampaignEntity.class);
    }

    // =====================================================================
    // Scoped metrics (Req 20.1)
    // =====================================================================

    /**
     * Feature: advertising-workspace-rework, Property 47: Goal metrics are scoped and propagation is
     * whitelisted.
     *
     * <p>Validates: Requirement 20.1.
     *
     * <p>Computing a Goal's metrics aggregates only the Campaigns associated with that Goal: the query
     * is scoped to the diagnosed Goal's id (never another Goal's id) and the aggregated totals equal
     * the totals over exactly the diagnosed Goal's Campaigns, so out-of-Goal Campaigns never leak in.
     */
    @Property(tries = MIN_ITERATIONS)
    @Label("Property 47: a Goal's metrics aggregate only its own Campaigns, never the whole Store")
    void goalMetricsAreScopedToTheGoalsCampaigns(@ForAll("metricScenarios") MetricScenario scenario) {
        CampaignMapper campaignMapper = mock(CampaignMapper.class);
        GoalMetricsService service = new GoalMetricsService(campaignMapper);

        // A store that mixes the diagnosed Goal's Campaigns with another Goal's Campaigns. The mapper
        // is modelled as a Goal-scoped query: only rows whose goal_id matches the wrapper's filter are
        // returned, exactly as a `WHERE goal_id = ?` query would behave.
        List<CampaignEntity> store = new ArrayList<>();
        store.addAll(scenario.goalCampaigns);
        store.addAll(scenario.otherGoalCampaigns);
        when(campaignMapper.selectList(any())).thenAnswer(inv ->
                store.stream()
                        .filter(c -> c.getGoalId() != null && c.getGoalId().equals(scopedGoalId(inv)))
                        .collect(Collectors.toList()));

        GoalMetrics metrics = service.computeGoalMetrics(scenario.goalId);

        // The aggregation reflects ONLY the diagnosed Goal's Campaigns.
        assertThat(metrics.getCampaignCount()).isEqualTo(scenario.goalCampaigns.size());

        BigDecimal expectedSpend = sum(scenario.goalCampaigns, CampaignEntity::getSpend);
        BigDecimal expectedSales = sum(scenario.goalCampaigns, CampaignEntity::getSales);
        int expectedOrders = scenario.goalCampaigns.stream().mapToInt(CampaignEntity::getOrders).sum();
        int expectedClicks = scenario.goalCampaigns.stream().mapToInt(CampaignEntity::getClicks).sum();
        long expectedImpressions = scenario.goalCampaigns.stream().mapToLong(CampaignEntity::getImpressions).sum();

        assertThat(metrics.getSpend()).isEqualByComparingTo(expectedSpend);
        assertThat(metrics.getSales()).isEqualByComparingTo(expectedSales);
        assertThat(metrics.getOrders()).isEqualTo(expectedOrders);
        assertThat(metrics.getClicks()).isEqualTo(expectedClicks);
        assertThat(metrics.getImpressions()).isEqualTo(expectedImpressions);

        // ACoS is produced as a decimal ratio (spend / sales) over the scoped Campaigns only.
        BigDecimal expectedAcos = expectedSales.signum() > 0
                ? AcosScale.normalizeRatio(expectedSpend.divide(expectedSales, AcosScale.RATIO_SCALE, RoundingMode.HALF_UP))
                : BigDecimal.ZERO;
        assertThat(metrics.getAcos()).isEqualByComparingTo(expectedAcos);

        // The query handed to the mapper is scoped to the diagnosed Goal, never the other Goal.
        List<UUID> filterIds = capturedGoalFilterValues(campaignMapper, store);
        assertThat(filterIds).contains(scenario.goalId);
        assertThat(filterIds).doesNotContain(scenario.otherGoalId);
    }

    // =====================================================================
    // Whitelisted propagation (Req 20.2, 20.3)
    // =====================================================================

    /**
     * Feature: advertising-workspace-rework, Property 47: Goal metrics are scoped and propagation is
     * whitelisted.
     *
     * <p>Validates: Requirements 20.2, 20.3.
     *
     * <p>A Goal update propagates exactly the target ACoS and the optimization goal (the whitelist),
     * never the Goal name / budget / budget type / personality, and never overwrites a Campaign-level
     * override for a propagated field — the Campaign-level value always wins.
     */
    @Property(tries = MIN_ITERATIONS)
    @Label("Property 47: a Goal update propagates only target ACoS + optimization goal and never overwrites a Campaign override")
    void goalUpdatePropagatesOnlyWhitelistedFieldsAndPreservesOverrides(
            @ForAll("propagationScenarios") PropagationScenario scenario) {

        GoalEntity goal = scenario.goal;
        List<CampaignEntity> campaigns = scenario.campaigns;

        // Snapshot the never-propagated (and overridable) fields before propagation.
        Map<UUID, String> namesBefore = new java.util.HashMap<>();
        Map<UUID, BigDecimal> budgetsBefore = new java.util.HashMap<>();
        Map<UUID, String> budgetTypesBefore = new java.util.HashMap<>();
        Map<UUID, String> personalitiesBefore = new java.util.HashMap<>();
        Map<UUID, BigDecimal> acosBefore = new java.util.HashMap<>();
        Map<UUID, String> hostingGoalBefore = new java.util.HashMap<>();
        for (CampaignEntity c : campaigns) {
            namesBefore.put(c.getId(), c.getName());
            budgetsBefore.put(c.getId(), c.getBudget());
            budgetTypesBefore.put(c.getId(), c.getBudgetType());
            personalitiesBefore.put(c.getId(), c.getCampaignPersonality());
            acosBefore.put(c.getId(), c.getTargetAcos());
            hostingGoalBefore.put(c.getId(), c.getHostingGoal());
        }

        GoalPropagationResult result = GoalMetricsService.planPropagation(goal, campaigns);

        BigDecimal goalAcos = goal.getTargetAcos() != null
                ? AcosScale.normalizeRatio(goal.getTargetAcos()) : null;
        String goalOptimizationGoal = blankToNull(goal.getType());

        for (CampaignEntity c : campaigns) {
            UUID id = c.getId();

            // Req 20.2: name, budget, budget type, and personality are NEVER propagated/overwritten.
            assertThat(c.getName()).isEqualTo(namesBefore.get(id));
            assertThat(c.getBudgetType()).isEqualTo(budgetTypesBefore.get(id));
            assertThat(c.getCampaignPersonality()).isEqualTo(personalitiesBefore.get(id));
            assertThat(bdEqual(c.getBudget(), budgetsBefore.get(id))).isTrue();

            boolean hadAcosOverride = acosBefore.get(id) != null;
            boolean hadGoalOverride = blankToNull(hostingGoalBefore.get(id)) != null;

            // --- target ACoS (whitelisted) ---
            boolean changedAcos;
            if (goalAcos == null) {
                // Goal carries no value: campaign target ACoS is left exactly as it was.
                assertThat(bdEqual(c.getTargetAcos(), acosBefore.get(id))).isTrue();
                changedAcos = false;
            } else if (hadAcosOverride) {
                // Req 20.3: a Campaign-level target ACoS override always wins.
                assertThat(bdEqual(c.getTargetAcos(), acosBefore.get(id))).isTrue();
                changedAcos = false;
            } else {
                // Overridable: the Goal's normalized target ACoS lands on the Campaign.
                assertThat(c.getTargetAcos()).isEqualByComparingTo(goalAcos);
                changedAcos = true;
            }

            // --- optimization goal (whitelisted) ---
            boolean changedGoal;
            if (goalOptimizationGoal == null) {
                assertThat(c.getHostingGoal()).isEqualTo(hostingGoalBefore.get(id));
                changedGoal = false;
            } else if (hadGoalOverride) {
                // Req 20.3: a Campaign-level optimization-goal override always wins.
                assertThat(c.getHostingGoal()).isEqualTo(hostingGoalBefore.get(id));
                changedGoal = false;
            } else {
                assertThat(c.getHostingGoal()).isEqualTo(goalOptimizationGoal);
                changedGoal = true;
            }

            boolean expectUpdated = changedAcos || changedGoal;
            boolean expectKeptOverride = (goalAcos != null && hadAcosOverride)
                    || (goalOptimizationGoal != null && hadGoalOverride);

            assertThat(result.getUpdatedCampaignIds().contains(id)).isEqualTo(expectUpdated);
            assertThat(result.getSkippedOverrideCampaignIds().contains(id)).isEqualTo(expectKeptOverride);
        }
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    private static BigDecimal sum(List<CampaignEntity> campaigns,
                                  java.util.function.Function<CampaignEntity, BigDecimal> field) {
        BigDecimal total = BigDecimal.ZERO;
        for (CampaignEntity c : campaigns) {
            BigDecimal v = field.apply(c);
            total = total.add(v == null ? BigDecimal.ZERO : v);
        }
        return total;
    }

    private static boolean bdEqual(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) {
            return a == b;
        }
        return a.compareTo(b) == 0;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** Read the goal-id value the service placed into the {@link LambdaQueryWrapper} of a query. */
    private static UUID scopedGoalId(InvocationOnMock invocation) {
        LambdaQueryWrapper<?> wrapper = invocation.getArgument(0);
        return filterValues(wrapper).stream()
                .filter(v -> v instanceof UUID)
                .map(UUID.class::cast)
                .findFirst()
                .orElse(null);
    }

    /**
     * Re-run the scoped query the service issues and collect the UUID filter values it bound, so the
     * test can assert the wrapper was scoped to the diagnosed Goal's id (and not another Goal's id).
     */
    private static List<UUID> capturedGoalFilterValues(CampaignMapper mapper, List<CampaignEntity> store) {
        // The service already invoked selectList during computeGoalMetrics; reconstruct the wrapper it
        // would build for the diagnosed goal by introspecting the captured invocation argument.
        org.mockito.ArgumentCaptor<LambdaQueryWrapper<CampaignEntity>> captor =
                org.mockito.ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        org.mockito.Mockito.verify(mapper).selectList(captor.capture());
        return filterValues(captor.getValue()).stream()
                .filter(v -> v instanceof UUID)
                .map(UUID.class::cast)
                .collect(Collectors.toList());
    }

    private static List<Object> filterValues(LambdaQueryWrapper<?> wrapper) {
        // MyBatis-Plus materialises bound parameter values lazily while building the SQL segment, so
        // force segment generation before reading the pairs.
        wrapper.getTargetSql();
        Map<String, Object> pairs = wrapper.getParamNameValuePairs();
        return new ArrayList<>(pairs.values());
    }

    // =====================================================================
    // Scenario types
    // =====================================================================

    /** Metrics scoping scenario: the diagnosed Goal's Campaigns plus another Goal's Campaigns. */
    static final class MetricScenario {
        final UUID goalId;
        final UUID otherGoalId;
        final List<CampaignEntity> goalCampaigns;
        final List<CampaignEntity> otherGoalCampaigns;

        MetricScenario(UUID goalId, UUID otherGoalId,
                       List<CampaignEntity> goalCampaigns, List<CampaignEntity> otherGoalCampaigns) {
            this.goalId = goalId;
            this.otherGoalId = otherGoalId;
            this.goalCampaigns = goalCampaigns;
            this.otherGoalCampaigns = otherGoalCampaigns;
        }
    }

    /** Propagation scenario: an updated Goal and a set of associated Campaigns with mixed overrides. */
    static final class PropagationScenario {
        final GoalEntity goal;
        final List<CampaignEntity> campaigns;

        PropagationScenario(GoalEntity goal, List<CampaignEntity> campaigns) {
            this.goal = goal;
            this.campaigns = campaigns;
        }
    }

    // =====================================================================
    // Generators
    // =====================================================================

    @Provide
    Arbitrary<MetricScenario> metricScenarios() {
        UUID goalId = UUID.randomUUID();
        UUID otherGoalId = UUID.randomUUID();
        Arbitrary<List<MetricCampaignSpec>> goalSpecs =
                metricCampaignSpec().list().ofMinSize(0).ofMaxSize(5);
        Arbitrary<List<MetricCampaignSpec>> otherSpecs =
                metricCampaignSpec().list().ofMinSize(0).ofMaxSize(5);
        return Combinators.combine(goalSpecs, otherSpecs).as((goal, other) -> {
            List<CampaignEntity> goalCampaigns = goal.stream()
                    .map(s -> s.toCampaign(goalId, false)).collect(Collectors.toList());
            // Out-of-Goal Campaigns carry deliberately huge, distinguishable spend so any leak would
            // change the aggregated totals.
            List<CampaignEntity> otherCampaigns = other.stream()
                    .map(s -> s.toCampaign(otherGoalId, true)).collect(Collectors.toList());
            return new MetricScenario(goalId, otherGoalId, goalCampaigns, otherCampaigns);
        });
    }

    @Provide
    Arbitrary<PropagationScenario> propagationScenarios() {
        Arbitrary<BigDecimal> goalAcos = Arbitraries.bigDecimals()
                .between(BigDecimal.valueOf(0.01), BigDecimal.valueOf(0.99))
                .ofScale(4)
                .injectNull(0.25);
        Arbitrary<String> goalType = Arbitraries.of(
                "maximize_sales_at_target", "increase_visibility", "balanced", "")
                .injectNull(0.25);
        Arbitrary<List<PropCampaignSpec>> specs =
                propCampaignSpec().list().ofMinSize(1).ofMaxSize(6);
        return Combinators.combine(goalAcos, goalType, specs).as((acos, type, campaignSpecs) -> {
            GoalEntity goal = GoalEntity.builder()
                    .id(UUID.randomUUID())
                    .storeId(UUID.randomUUID())
                    .name("goal-" + UUID.randomUUID())
                    .type(type)
                    .targetAcos(acos)
                    .dailyBudget(BigDecimal.valueOf(123.45))
                    .build();
            List<CampaignEntity> campaigns = campaignSpecs.stream()
                    .map(PropCampaignSpec::toCampaign).collect(Collectors.toList());
            return new PropagationScenario(goal, campaigns);
        });
    }

    // --- metric campaign spec -------------------------------------------------

    static final class MetricCampaignSpec {
        final long spendCents;
        final long salesCents;
        final int orders;
        final int clicks;
        final long impressions;

        MetricCampaignSpec(long spendCents, long salesCents, int orders, int clicks, long impressions) {
            this.spendCents = spendCents;
            this.salesCents = salesCents;
            this.orders = orders;
            this.clicks = clicks;
            this.impressions = impressions;
        }

        CampaignEntity toCampaign(UUID goalId, boolean huge) {
            BigDecimal scale = huge ? BigDecimal.valueOf(1_000_000) : BigDecimal.ONE;
            return CampaignEntity.builder()
                    .id(UUID.randomUUID())
                    .goalId(goalId)
                    .storeId(UUID.randomUUID())
                    .name("c-" + UUID.randomUUID())
                    .spend(BigDecimal.valueOf(spendCents, 2).add(huge ? scale : BigDecimal.ZERO))
                    .sales(BigDecimal.valueOf(salesCents, 2).add(huge ? scale : BigDecimal.ZERO))
                    .orders(huge ? orders + 1_000_000 : orders)
                    .clicks(huge ? clicks + 1_000_000 : clicks)
                    .impressions(huge ? impressions + 1_000_000L : impressions)
                    .build();
        }
    }

    @Provide
    Arbitrary<MetricCampaignSpec> metricCampaignSpec() {
        return Combinators.combine(
                        Arbitraries.longs().between(0, 5_000_00),
                        Arbitraries.longs().between(0, 5_000_00),
                        Arbitraries.integers().between(0, 500),
                        Arbitraries.integers().between(0, 5_000),
                        Arbitraries.longs().between(0, 100_000))
                .as(MetricCampaignSpec::new);
    }

    // --- propagation campaign spec -------------------------------------------

    static final class PropCampaignSpec {
        final BigDecimal existingAcos;       // null = no Campaign-level ACoS override
        final String existingHostingGoal;    // null/blank = no Campaign-level optimization-goal override
        final String name;
        final BigDecimal budget;
        final String budgetType;
        final String personality;

        PropCampaignSpec(BigDecimal existingAcos, String existingHostingGoal, String name,
                         BigDecimal budget, String budgetType, String personality) {
            this.existingAcos = existingAcos;
            this.existingHostingGoal = existingHostingGoal;
            this.name = name;
            this.budget = budget;
            this.budgetType = budgetType;
            this.personality = personality;
        }

        CampaignEntity toCampaign() {
            return CampaignEntity.builder()
                    .id(UUID.randomUUID())
                    .goalId(UUID.randomUUID())
                    .storeId(UUID.randomUUID())
                    .name(name)
                    .budget(budget)
                    .budgetType(budgetType)
                    .campaignPersonality(personality)
                    .targetAcos(existingAcos)
                    .hostingGoal(existingHostingGoal)
                    .build();
        }
    }

    @Provide
    Arbitrary<PropCampaignSpec> propCampaignSpec() {
        Arbitrary<BigDecimal> existingAcos = Arbitraries.bigDecimals()
                .between(BigDecimal.valueOf(0.05), BigDecimal.valueOf(0.80))
                .ofScale(4)
                .injectNull(0.5);
        Arbitrary<String> existingHostingGoal = Arbitraries.of(
                "campaign_override_goal_a", "campaign_override_goal_b", "")
                .injectNull(0.5);
        Arbitrary<String> name = Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(20);
        Arbitrary<BigDecimal> budget = Arbitraries.bigDecimals()
                .between(BigDecimal.ONE, BigDecimal.valueOf(1000))
                .ofScale(2)
                .injectNull(0.2);
        Arbitrary<String> budgetType = Arbitraries.of("daily", "lifetime");
        Arbitrary<String> personality = Arbitraries.of("aggressive", "balanced", "conservative")
                .injectNull(0.4);
        return Combinators.combine(existingAcos, existingHostingGoal, name, budget, budgetType, personality)
                .as(PropCampaignSpec::new);
    }
}
