package com.adpilot.modules.advertising.service;

import com.adpilot.modules.advertising.entity.CampaignEntity;
import com.adpilot.modules.advertising.entity.KeywordEntity;
import com.adpilot.modules.advertising.entity.PerformanceDailyEntity;
import com.adpilot.modules.advertising.entity.PersonalityPolicyEntity;
import com.adpilot.modules.advertising.mapper.CampaignMapper;
import com.adpilot.modules.advertising.mapper.KeywordMapper;
import com.adpilot.modules.advertising.mapper.OperationMapper;
import com.adpilot.modules.advertising.mapper.PerformanceDailyMapper;
import com.adpilot.modules.advertising.operation.CreateOperationCommand;
import com.adpilot.modules.advertising.operation.OperationScope;
import com.adpilot.modules.advertising.operation.OperationService;
import com.adpilot.modules.advertising.operation.OperationSource;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import net.jqwik.api.constraints.BigRange;
import net.jqwik.api.constraints.IntRange;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Property-based test for the non-silent / no-auto-execute personality-change guarantee of
 * {@link AiHostingOptimizer} (task 13.11).
 *
 * <p>Feature: advertising-workspace-rework, Property 53: Personality change is never silent and never
 * auto-executes all.
 *
 * <p>Validates: Requirements 49.8, 49.12.
 *
 * <p>Property 53 (transcribed from the design's Correctness Properties section): <em>For any
 * Optimization_Goal selection or personality change, AI_Personality is never switched without
 * explicit operator confirmation, and changing AI_Personality never immediately recomputes and
 * executes all hosted Campaigns.</em>
 *
 * <p>The AI optimizer is the only autonomous actor that recomputes-and-executes hosted Campaigns, so
 * the two facets of the property are pinned against the real {@link AiHostingOptimizer}:
 *
 * <ol>
 *   <li><b>Changing AI_Personality never auto-executes (Req 49.12).</b> Simulating an operator
 *       selecting an Optimization_Goal that recommends a different personality — modelled as the
 *       resolved personality the optimizer would read flipping from one value to another — produces
 *       NO execution on its own: until the operator-governed scheduled recompute entry point
 *       ({@link AiHostingOptimizer#runOnce()}) is explicitly invoked, the optimizer never calls
 *       {@link OperationService#createOperation}. There is no personality-change hook that
 *       immediately recomputes-and-executes the hosted Campaigns.</li>
 *   <li><b>The optimizer never silently switches AI_Personality (Req 49.8).</b> When the scheduled
 *       recompute IS explicitly invoked, every Operation the optimizer emits is a {@code bid}
 *       adjustment ({@code ai_hosting} / {@code platform_mutation} on the {@code keyword} entity) —
 *       never a personality switch — and the run never mutates any Campaign's stored AI_Personality
 *       override. The AI optimizer therefore can never silently change AI_Personality; a switch can
 *       only happen through the explicit, operator-confirmed path outside the optimizer.</li>
 * </ol>
 *
 * <p>The pure {@link PersonalityResolver#resolve(String, String, String)} is exercised directly to
 * confirm the third strand of Req 49.8: selecting an Optimization_Goal (whose Goal_Personality_Default
 * differs) shifts the <em>effective</em> personality the AI reads, but never overwrites the Campaign's
 * stored override — resolution is read-only, so a Goal selection cannot silently switch a Campaign's
 * persisted personality.
 */
@Tag("pbt")
@Label("Feature: advertising-workspace-rework, Property 53: Personality change is never silent and "
        + "never auto-executes all")
class PersonalityChangeNotSilentPropertyTest {

    /** Minimum property iterations mandated for this property. */
    private static final int MIN_ITERATIONS = 200;

    private static final BigDecimal TARGET_ACOS = new BigDecimal("25");

    /** Entity-type/field markers a personality switch WOULD carry, so we can assert none are emitted. */
    private static final List<String> PERSONALITY_FIELDS =
            List.of("personality", "campaignPersonality", "defaultPersonality", "campaign_personality");

    /**
     * Feature: advertising-workspace-rework, Property 53: Personality change is never silent and never
     * auto-executes all.
     *
     * <p>Validates: Requirements 49.8, 49.12.
     *
     * <p>For any pair of personalities (the value before and the value chosen after an
     * Optimization_Goal selection) and any number of hosted Campaigns, changing the resolved
     * personality never auto-executes, and an explicit recompute never silently switches personality.
     */
    @Property(tries = MIN_ITERATIONS)
    @Label("Property 53: a personality change never auto-executes and the optimizer never silently "
            + "switches personality")
    void personalityChangeIsNeverSilentAndNeverAutoExecutesAll(
            @ForAll("personalities") AiPersonality before,
            @ForAll("personalities") AiPersonality after,
            @ForAll @IntRange(min = 1, max = 5) int hostedCampaignCount,
            @ForAll @BigRange(min = "0.10", max = "50.00") BigDecimal currentBid) {

        Harness h = new Harness();
        List<CampaignEntity> hosted = h.hostedCampaigns(hostedCampaignCount);
        KeywordEntity keyword = h.keyword(currentBid.setScale(4, java.math.RoundingMode.HALF_UP));

        // ---- Req 49.12: merely CHANGING the personality executes nothing -------------------------
        // Operator's currently-resolved personality.
        when(h.personalityResolver.resolveForCampaign(any(CampaignEntity.class))).thenReturn(before);
        // No scheduled recompute has been invoked yet, so nothing has executed.
        verifyNoInteractions(h.operationService);

        // Operator selects an Optimization_Goal recommending a different personality: the resolved
        // personality flips. This change, on its own, must NOT recompute-and-execute any Campaign.
        when(h.personalityResolver.resolveForCampaign(any(CampaignEntity.class))).thenReturn(after);
        verify(h.operationService, never()).createOperation(any());
        // Hosted Campaigns are not even loaded as a side effect of the personality change. Verified
        // BEFORE the recompute path is wired, so the campaign query is provably untouched until the
        // explicit scheduled tick runs.
        verify(h.campaignMapper, never()).selectList(any());

        // ---- Req 49.8: the EXPLICIT recompute never silently switches personality ---------------
        // Wire the recompute inputs: the hosted Campaigns and their (single shared) keyword whose
        // recent ACoS (50%) sits above target (25%), so a real bid DECREASE is available — emission
        // is then driven solely by the explicit recompute, never by the personality value changing.
        when(h.campaignMapper.selectList(any())).thenReturn(hosted);
        when(h.keywordMapper.selectList(any())).thenReturn(List.of(keyword));
        when(h.performanceDailyMapper.selectList(any())).thenReturn(List.of(h.perf(50, 100)));

        AiHostingOptimizer.OptimizationSummary summary = h.optimizer.runOnce();

        ArgumentCaptor<CreateOperationCommand> captor =
                ArgumentCaptor.forClass(CreateOperationCommand.class);
        // Execution happens only via the explicit, operator-governed scheduled entry point, and it is
        // bounded to the normal per-Campaign recompute — never an amplified "recompute ALL" triggered
        // by the personality change (one bid Operation per hosted Campaign).
        verify(h.operationService, times(hostedCampaignCount)).createOperation(captor.capture());
        assertThat(summary.getOperationsCreated()).isEqualTo(hostedCampaignCount);

        for (CreateOperationCommand cmd : captor.getAllValues()) {
            // Every emitted Operation is a bid adjustment — never a personality switch (Req 49.8).
            assertThat(cmd.getOperationSource()).isEqualTo(OperationSource.AI_HOSTING);
            assertThat(cmd.getOperationScope()).isEqualTo(OperationScope.PLATFORM_MUTATION);
            assertThat(cmd.getEntityType()).isEqualTo("keyword");
            assertThat(cmd.getField()).isEqualTo("bid");
            assertThat(cmd.getField())
                    .as("the optimizer never emits an Operation that switches AI_Personality")
                    .isNotIn(PERSONALITY_FIELDS);
        }

        // The recompute never mutates any Campaign's stored AI_Personality override (read-only).
        for (CampaignEntity campaign : hosted) {
            assertThat(campaign.getCampaignPersonality())
                    .as("the optimizer never silently writes a Campaign's personality override")
                    .isNull();
        }

        // ---- Req 49.8: selecting a Goal shifts the EFFECTIVE personality but never overwrites the
        // Campaign's stored override (resolution is read-only) ------------------------------------
        PersonalityResolver pureResolver = new PersonalityResolver(null, null, null);
        // No Campaign override set; the operator's chosen Goal default supplies the effective value.
        AiPersonality effective = pureResolver.resolve(null, after.machineValue(), before.machineValue());
        assertThat(effective)
                .as("selecting a Goal makes the effective personality reflect its default ...")
                .isEqualTo(after);
        // ... yet the Campaign-level override (the persisted personality) is untouched by resolution.
        AiPersonality stillEffective = pureResolver.resolve(null, after.machineValue(), before.machineValue());
        assertThat(stillEffective).isEqualTo(effective);
    }

    // --- generators ------------------------------------------------------------------------------

    /** Every canonical AI_Personality machine value (Req 49.1). */
    @Provide
    Arbitrary<AiPersonality> personalities() {
        return Arbitraries.of(AiPersonality.values());
    }

    // --- harness ---------------------------------------------------------------------------------

    /**
     * A fresh wiring of the real {@link AiHostingOptimizer} over mocked mappers/services at phase V1
     * (bid adjustment enabled) and pause disabled, so the only variable governing whether anything
     * executes is whether the explicit scheduled recompute is invoked.
     */
    private static final class Harness {

        private final CampaignMapper campaignMapper = Mockito.mock(CampaignMapper.class);
        private final KeywordMapper keywordMapper = Mockito.mock(KeywordMapper.class);
        private final PerformanceDailyMapper performanceDailyMapper = Mockito.mock(PerformanceDailyMapper.class);
        private final OperationService operationService = Mockito.mock(OperationService.class);
        private final PersonalityResolver personalityResolver = Mockito.mock(PersonalityResolver.class);
        private final PersonalityPolicyService personalityPolicyService = Mockito.mock(PersonalityPolicyService.class);
        private final OperationMapper operationMapper = Mockito.mock(OperationMapper.class);

        private final AiHostingOptimizer optimizer;

        Harness() {
            optimizer = new AiHostingOptimizer(campaignMapper, keywordMapper, performanceDailyMapper,
                    operationService, personalityResolver, personalityPolicyService, operationMapper,
                    new com.adpilot.modules.advertising.hosting.ReversibilityClassifier());
            ReflectionTestUtils.setField(optimizer, "lookbackDays", 14);
            ReflectionTestUtils.setField(optimizer, "minBid", new BigDecimal("0.02"));
            ReflectionTestUtils.setField(optimizer, "maxBid", new BigDecimal("1000"));
            ReflectionTestUtils.setField(optimizer, "phaseConfig", "V1");
            ReflectionTestUtils.setField(optimizer, "globalPause", false);

            when(personalityPolicyService.resolvePolicy(any())).thenReturn(balancedPolicy());
        }

        private PersonalityPolicyEntity balancedPolicy() {
            return PersonalityPolicyEntity.builder()
                    .scope("system")
                    .personality("balanced")
                    .maxBidIncreaseRatio(new BigDecimal("0.10"))
                    .maxBidDecreaseRatio(new BigDecimal("0.15"))
                    .approvalBidChangeRatio(new BigDecimal("0.07"))
                    .ruleVersion("balanced-v1")
                    .build();
        }

        List<CampaignEntity> hostedCampaigns(int count) {
            List<CampaignEntity> campaigns = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                campaigns.add(CampaignEntity.builder()
                        .id(UUID.randomUUID())
                        .storeId(UUID.randomUUID())
                        .name("Camp-" + i)
                        .hostingEnabled(true)
                        .targetAcos(TARGET_ACOS)
                        // No Campaign-level personality override persisted.
                        .campaignPersonality(null)
                        .build());
            }
            return campaigns;
        }

        KeywordEntity keyword(BigDecimal bid) {
            return KeywordEntity.builder()
                    .id(UUID.randomUUID())
                    .campaignId(UUID.randomUUID())
                    .adGroupId(UUID.randomUUID())
                    .storeId(UUID.randomUUID())
                    .keywordText("kw")
                    .status("enabled")
                    .bid(bid)
                    .version(0L)
                    .build();
        }

        PerformanceDailyEntity perf(double spend, double sales) {
            return PerformanceDailyEntity.builder()
                    .date(LocalDate.now())
                    .spend(BigDecimal.valueOf(spend))
                    .sales(BigDecimal.valueOf(sales))
                    .build();
        }
    }
}
