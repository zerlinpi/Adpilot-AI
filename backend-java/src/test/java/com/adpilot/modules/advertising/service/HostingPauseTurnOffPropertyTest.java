package com.adpilot.modules.advertising.service;

import com.adpilot.modules.advertising.entity.KeywordEntity;
import com.adpilot.modules.advertising.entity.OperationEntity;
import com.adpilot.modules.advertising.mapper.CampaignMapper;
import com.adpilot.modules.advertising.mapper.KeywordMapper;
import com.adpilot.modules.advertising.mapper.OperationMapper;
import com.adpilot.modules.advertising.mapper.PerformanceDailyMapper;
import com.adpilot.modules.advertising.operation.OperationMachineValues;
import com.adpilot.modules.advertising.operation.OperationService;
import com.adpilot.modules.advertising.operation.OperationSource;
import com.adpilot.modules.advertising.operation.SyncState;
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
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Property-based test for the AI-hosting pause / turn-off guard of {@link AiHostingOptimizer}.
 *
 * <p>Feature: advertising-workspace-rework, Property 54: Hosting pause/turn-off stops new ops and
 * resolves in-flight ones.
 *
 * <p>Validates: Requirements 22.9, 22.12.
 *
 * <p>For any Campaign whose hosting is turned off, or while the global pause switch is engaged, the
 * optimizer:
 * <ul>
 *   <li>generates NO new hosting Operations (Req 22.9) — {@link AiHostingOptimizer#runOnce()} reports
 *       {@code paused}, never loads hosted campaigns, and never calls
 *       {@link OperationService#createOperation};</li>
 *   <li>cancels exactly the {@code ai_hosting} Operations sitting in {@code awaiting_approval}
 *       (Req 22.12) — both for the all-hosting global-pause path
 *       ({@link AiHostingOptimizer#cancelAwaitingApprovalForAllHosting()}) and the per-Campaign
 *       turn-off path ({@link AiHostingOptimizer#cancelAwaitingApprovalForCampaign(UUID)}); and</li>
 *   <li>leaves every other Operation untouched — in particular the already-submitted / in-flight
 *       {@code submitted} and {@code amazon-processing} hosting Operations are left to resolve their
 *       platform final state rather than being silently dropped, and are never even selected by the
 *       cancellation query.</li>
 * </ul>
 *
 * <p>The mapper is modelled as a store filtered exactly by the {@link LambdaQueryWrapper} the
 * optimizer builds (introspected via MyBatis-Plus bound parameters, as in the table-view isolation
 * property test), so the assertions test the optimizer's real query scoping rather than a restated
 * filter.
 */
class HostingPauseTurnOffPropertyTest {

    static {
        // Register entity metadata so MyBatis-Plus can resolve lambda columns and materialise the
        // bound parameter values for query-wrapper introspection, without a running Spring context.
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, OperationEntity.class);
    }

    private static final String AI_HOSTING = OperationMachineValues.toValue(OperationSource.AI_HOSTING);
    private static final String AWAITING_APPROVAL = OperationMachineValues.toValue(SyncState.AWAITING_APPROVAL);
    private static final String SUBMITTED = OperationMachineValues.toValue(SyncState.SUBMITTED);
    private static final String AMAZON_PROCESSING = OperationMachineValues.toValue(SyncState.AMAZON_PROCESSING);

    /** Every Operation_Source machine value, so non-hosting sources are exercised too. */
    private static final List<String> SOURCE_VALUES = List.of(
            OperationMachineValues.toValue(OperationSource.MANUAL),
            OperationMachineValues.toValue(OperationSource.RECOMMENDATION),
            OperationMachineValues.toValue(OperationSource.ONE_CLICK_OPTIMIZE),
            OperationMachineValues.toValue(OperationSource.AI_HOSTING),
            OperationMachineValues.toValue(OperationSource.CREATION));

    /** Every Sync_State machine value, so in-flight and terminal states are exercised too. */
    private static final List<String> SYNC_STATE_VALUES = java.util.Arrays.stream(SyncState.values())
            .map(OperationMachineValues::toValue)
            .collect(Collectors.toList());

    /**
     * Feature: advertising-workspace-rework, Property 54: Hosting pause/turn-off stops new ops and
     * resolves in-flight ones.
     *
     * <p>Validates: Requirements 22.9, 22.12.
     */
    @Property(tries = 200)
    @Tag("pbt")
    @Label("Feature: advertising-workspace-rework, Property 54: Hosting pause/turn-off stops new ops "
            + "and resolves in-flight ones")
    void hostingPauseTurnOffStopsNewOpsAndResolvesInFlight(
            @ForAll("operationStores") List<OperationEntity> store,
            @ForAll("campaignKeywordCounts") int keywordCount) {

        // ---- Req 22.9: while paused, runOnce generates NO new hosting Operations ----------------
        Harness pausedRun = newHarness(true);
        AiHostingOptimizer.OptimizationSummary summary = pausedRun.optimizer.runOnce();
        assertThat(summary.isPaused()).isTrue();
        assertThat(summary.getOperationsCreated()).isZero();
        // No hosted campaigns are even loaded and no Operation is created while paused.
        verify(pausedRun.campaignMapper, never()).selectList(any());
        verify(pausedRun.operationService, never()).createOperation(any());

        // ---- Req 22.12 (global pause): cancel awaiting_approval hosting ops, leave the rest -----
        Harness all = newHarness(true);
        when(all.operationMapper.selectList(any()))
                .thenAnswer(inv -> selectMatching(store, inv));

        int cancelledAll = all.optimizer.cancelAwaitingApprovalForAllHosting();

        List<OperationEntity> expectedAll = store.stream()
                .filter(o -> AI_HOSTING.equals(o.getOperationSource()))
                .filter(o -> AWAITING_APPROVAL.equals(o.getSyncState()))
                .collect(Collectors.toList());

        assertThat(cancelledAll).isEqualTo(expectedAll.size());
        assertOnlyExpectedCancelled(all.operationService, store, expectedAll);
        assertQueryExcludesInFlight(all.operationMapper);

        // ---- Req 22.12 (per-Campaign turn-off): same guarantee, scoped to one Campaign ----------
        UUID campaignId = UUID.randomUUID();
        List<KeywordEntity> keywords = new ArrayList<>();
        Set<UUID> scopedEntityIds = new java.util.HashSet<>();
        scopedEntityIds.add(campaignId);
        for (int i = 0; i < keywordCount; i++) {
            KeywordEntity kw = KeywordEntity.builder()
                    .id(UUID.randomUUID())
                    .campaignId(campaignId)
                    .build();
            keywords.add(kw);
            scopedEntityIds.add(kw.getId());
        }
        // Re-key half the store onto this campaign's entities so the scope filter is exercised both
        // ways (some hosting awaiting_approval ops in-scope, some out-of-scope).
        List<OperationEntity> scopedStore = rekeyOntoScope(store, scopedEntityIds);

        Harness perCampaign = newHarness(true);
        when(perCampaign.keywordMapper.selectList(any())).thenReturn(keywords);
        when(perCampaign.operationMapper.selectList(any()))
                .thenAnswer(inv -> selectMatching(scopedStore, inv));

        int cancelledScoped = perCampaign.optimizer.cancelAwaitingApprovalForCampaign(campaignId);

        List<OperationEntity> expectedScoped = scopedStore.stream()
                .filter(o -> AI_HOSTING.equals(o.getOperationSource()))
                .filter(o -> AWAITING_APPROVAL.equals(o.getSyncState()))
                .filter(o -> scopedEntityIds.contains(o.getEntityId()))
                .collect(Collectors.toList());

        assertThat(cancelledScoped).isEqualTo(expectedScoped.size());
        assertOnlyExpectedCancelled(perCampaign.operationService, scopedStore, expectedScoped);
        assertQueryExcludesInFlight(perCampaign.operationMapper);
    }

    // --- assertions --------------------------------------------------------

    /**
     * Exactly the expected awaiting_approval hosting Operations are cancelled; every other Operation
     * — including the in-flight {@code submitted}/{@code amazon-processing} hosting ones — is left to
     * resolve and is never cancelled.
     */
    private static void assertOnlyExpectedCancelled(OperationService operationService,
                                                    List<OperationEntity> store,
                                                    List<OperationEntity> expected) {
        for (OperationEntity op : expected) {
            verify(operationService).cancel(eq(op.getId()));
        }
        for (OperationEntity op : store) {
            if (!expected.contains(op)) {
                verify(operationService, never()).cancel(eq(op.getId()));
            }
        }
    }

    /** The cancellation query is scoped to ai_hosting + awaiting_approval and never selects in-flight states. */
    private static void assertQueryExcludesInFlight(OperationMapper operationMapper) {
        List<Object> params = capturedSelectParams(operationMapper);
        assertThat(params).contains(AI_HOSTING, AWAITING_APPROVAL);
        assertThat(params).doesNotContain(SUBMITTED, AMAZON_PROCESSING);
    }

    // --- mapper-as-store model ---------------------------------------------

    /**
     * Model the mapper as a store filtered exactly by the wrapper the optimizer built: a row is
     * visible only when its source and sync_state match the wrapper's string filters and (when the
     * wrapper carries an entity-id {@code IN} clause) its entity_id is one of the scoped ids.
     */
    private static List<OperationEntity> selectMatching(List<OperationEntity> store, InvocationOnMock inv) {
        LambdaQueryWrapper<OperationEntity> wrapper = inv.getArgument(0);
        List<Object> params = filterValues(wrapper);
        Set<String> strings = params.stream()
                .filter(String.class::isInstance).map(String.class::cast).collect(Collectors.toSet());
        Set<UUID> uuids = params.stream()
                .filter(UUID.class::isInstance).map(UUID.class::cast).collect(Collectors.toSet());
        return store.stream()
                .filter(o -> strings.contains(o.getOperationSource()))
                .filter(o -> strings.contains(o.getSyncState()))
                .filter(o -> uuids.isEmpty() || uuids.contains(o.getEntityId()))
                .collect(Collectors.toList());
    }

    private static List<Object> capturedSelectParams(OperationMapper operationMapper) {
        @SuppressWarnings("unchecked")
        org.mockito.ArgumentCaptor<LambdaQueryWrapper<OperationEntity>> captor =
                org.mockito.ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(operationMapper).selectList(captor.capture());
        return filterValues(captor.getValue());
    }

    private static List<Object> filterValues(LambdaQueryWrapper<?> wrapper) {
        // MyBatis-Plus materialises bound parameter values lazily while building the SQL segment,
        // so force segment generation before reading the pairs.
        wrapper.getTargetSql();
        Map<String, Object> pairs = wrapper.getParamNameValuePairs();
        return new ArrayList<>(pairs.values());
    }

    private static List<OperationEntity> rekeyOntoScope(List<OperationEntity> store, Set<UUID> scopedIds) {
        List<UUID> scoped = new ArrayList<>(scopedIds);
        List<OperationEntity> out = new ArrayList<>(store.size());
        for (int i = 0; i < store.size(); i++) {
            OperationEntity src = store.get(i);
            UUID entityId = (i % 2 == 0 && !scoped.isEmpty())
                    ? scoped.get(i % scoped.size())
                    : src.getEntityId();
            out.add(OperationEntity.builder()
                    .id(src.getId())
                    .operationSource(src.getOperationSource())
                    .syncState(src.getSyncState())
                    .entityId(entityId)
                    .build());
        }
        return out;
    }

    // --- harness -----------------------------------------------------------

    private record Harness(AiHostingOptimizer optimizer,
                           CampaignMapper campaignMapper,
                           KeywordMapper keywordMapper,
                           OperationService operationService,
                           OperationMapper operationMapper) {
    }

    private static Harness newHarness(boolean globalPause) {
        CampaignMapper campaignMapper = Mockito.mock(CampaignMapper.class);
        KeywordMapper keywordMapper = Mockito.mock(KeywordMapper.class);
        PerformanceDailyMapper performanceDailyMapper = Mockito.mock(PerformanceDailyMapper.class);
        OperationService operationService = Mockito.mock(OperationService.class);
        PersonalityResolver personalityResolver = Mockito.mock(PersonalityResolver.class);
        PersonalityPolicyService personalityPolicyService = Mockito.mock(PersonalityPolicyService.class);
        OperationMapper operationMapper = Mockito.mock(OperationMapper.class);

        AiHostingOptimizer optimizer = new AiHostingOptimizer(campaignMapper, keywordMapper,
                performanceDailyMapper, operationService, personalityResolver,
                personalityPolicyService, operationMapper,
                new com.adpilot.modules.advertising.hosting.ReversibilityClassifier());
        ReflectionTestUtils.setField(optimizer, "lookbackDays", 14);
        ReflectionTestUtils.setField(optimizer, "minBid", new BigDecimal("0.02"));
        ReflectionTestUtils.setField(optimizer, "maxBid", new BigDecimal("1000"));
        ReflectionTestUtils.setField(optimizer, "phaseConfig", "V1");
        ReflectionTestUtils.setField(optimizer, "globalPause", globalPause);

        return new Harness(optimizer, campaignMapper, keywordMapper, operationService, operationMapper);
    }

    // --- generators --------------------------------------------------------

    /**
     * A store of Operations spanning every source and sync_state, with unique ids and entity ids, so
     * the cancellation guard is exercised against hosting awaiting_approval rows, hosting in-flight
     * rows, hosting terminal rows, and non-hosting rows in the same iteration.
     */
    @Provide
    Arbitrary<List<OperationEntity>> operationStores() {
        return operation().list().ofMinSize(0).ofMaxSize(14);
    }

    private Arbitrary<OperationEntity> operation() {
        Arbitrary<String> sources = Arbitraries.of(SOURCE_VALUES);
        Arbitrary<String> states = Arbitraries.of(SYNC_STATE_VALUES);
        return Combinators.combine(sources, states).as((source, state) ->
                OperationEntity.builder()
                        .id(UUID.randomUUID())
                        .operationSource(source)
                        .syncState(state)
                        .entityId(UUID.randomUUID())
                        .build());
    }

    /** Number of keywords owned by the turned-off Campaign in the per-Campaign scenario. */
    @Provide
    Arbitrary<Integer> campaignKeywordCounts() {
        return Arbitraries.integers().between(0, 4);
    }
}
