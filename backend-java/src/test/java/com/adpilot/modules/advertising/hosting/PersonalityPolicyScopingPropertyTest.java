package com.adpilot.modules.advertising.hosting;

import com.adpilot.modules.advertising.entity.PersonalityPolicyEntity;
import com.adpilot.modules.advertising.mapper.PersonalityPolicyMapper;
import com.adpilot.modules.advertising.service.PersonalityPolicyScopeService;
import com.adpilot.modules.advertising.service.impl.PersonalityPolicyScopeServiceImpl;
import com.adpilot.modules.audit.mapper.AuditLogMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.GenerationMode;
import net.jqwik.api.Label;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Tag;
import net.jqwik.api.lifecycle.BeforeProperty;
import org.apache.ibatis.builder.MapperBuilderAssistant;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Property-based test for the personality policy scoping service logic.
 *
 * <p>Feature: amazon-ads-ai-hosting-system, Property 43: Personality policy single-active
 * and most-specific resolution.
 *
 * <p><b>Validates: Requirements 38.2, 38.3</b>
 *
 * <p>Properties tested:
 * <ol>
 *   <li>At most one active version exists per (scope, scope_id, personality) — activating a
 *       new version deactivates any previous one (Req 38.2)</li>
 *   <li>Resolution returns the most-specific active scope: campaign → goal → store →
 *       organization → system (Req 38.3)</li>
 *   <li>When a more-specific scope is active, it overrides any less-specific scope</li>
 *   <li>When no scope has an active policy, resolution throws (returns null/empty)</li>
 * </ol>
 */
@Tag("pbt")
@Label("Feature: amazon-ads-ai-hosting-system, Property 43: Personality policy single-active and most-specific resolution")
class PersonalityPolicyScopingPropertyTest {

    private static final int MIN_ITERATIONS = 100;

    private static final String STATUS_ACTIVE = "active";
    private static final String STATUS_INACTIVE = "inactive";

    /** The 5 scope levels in precedence order (most-specific first). */
    private static final List<String> SCOPE_PRECEDENCE = List.of(
            "campaign", "goal", "store", "organization", "system"
    );

    private static final AtomicBoolean TABLE_INFO_INITIALIZED = new AtomicBoolean(false);

    @BeforeProperty
    void initTableInfo() {
        // Initialize MyBatis-Plus lambda cache for PersonalityPolicyEntity so
        // LambdaQueryWrapper/LambdaUpdateWrapper can resolve column mappings.
        if (TABLE_INFO_INITIALIZED.compareAndSet(false, true)) {
            MybatisConfiguration configuration = new MybatisConfiguration();
            MapperBuilderAssistant assistant = new MapperBuilderAssistant(
                    configuration, "");
            assistant.setCurrentNamespace("com.adpilot.modules.advertising.mapper.PersonalityPolicyMapper");
            TableInfoHelper.initTableInfo(assistant, PersonalityPolicyEntity.class);
        }
    }

    // ================================================================================
    // Property 1: Single-active invariant (Req 38.2)
    // ================================================================================

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 43: Personality policy single-active
     * and most-specific resolution.
     *
     * <p><b>Validates: Requirements 38.2</b>
     *
     * <p>Activating a new version for the same (scope, scope_id, personality) deactivates
     * any previously active version. After activation, at most one version is active.
     */
    @Property(tries = MIN_ITERATIONS, generation = GenerationMode.RANDOMIZED)
    @Label("Single-active: activating a new version deactivates the previous one for the same scope tuple")
    void activatingNewVersionDeactivatesPrevious(
            @ForAll("activationScenarios") ActivationScenario scenario) {

        // Track policy states in an in-memory store
        Map<UUID, PersonalityPolicyEntity> policyStore = new ConcurrentHashMap<>();

        PersonalityPolicyEntity existing = buildPolicy(
                scenario.existingId(), scenario.scope(), scenario.scopeId(),
                scenario.personality(), STATUS_ACTIVE, "v1");
        PersonalityPolicyEntity newPolicy = buildPolicy(
                scenario.newPolicyId(), scenario.scope(), scenario.scopeId(),
                scenario.personality(), STATUS_INACTIVE, "v2");

        policyStore.put(existing.getId(), existing);
        policyStore.put(newPolicy.getId(), newPolicy);

        PersonalityPolicyMapper mapper = mock(PersonalityPolicyMapper.class);
        AuditLogMapper auditLogMapper = mock(AuditLogMapper.class);

        when(mapper.selectById(any(UUID.class))).thenAnswer(inv -> {
            UUID id = inv.getArgument(0);
            return policyStore.get(id);
        });

        // selectOne: find the currently active policy for the (scope, scope_id, personality)
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenAnswer(inv -> policyStore.values().stream()
                .filter(p -> STATUS_ACTIVE.equals(p.getStatus()))
                .filter(p -> p.getScope().equals(scenario.scope()))
                .filter(p -> Objects.equals(p.getScopeId(), scenario.scopeId()))
                .filter(p -> p.getPersonality().equals(scenario.personality()))
                .findFirst()
                .orElse(null));

        // update: simulate the activation/deactivation in the store
        when(mapper.update(any(), any(LambdaUpdateWrapper.class))).thenAnswer(inv -> {
            // The service calls update twice: first to deactivate the existing, then to activate the new one.
            // We determine which by checking the current state.
            // First call: deactivate existing (changes existing status to inactive)
            if (STATUS_ACTIVE.equals(existing.getStatus())) {
                existing.setStatus(STATUS_INACTIVE);
            } else {
                // Second call: activate the new one
                newPolicy.setStatus(STATUS_ACTIVE);
            }
            return 1;
        });

        when(auditLogMapper.insert(any())).thenReturn(1);

        PersonalityPolicyScopeServiceImpl service = new PersonalityPolicyScopeServiceImpl(
                mapper, auditLogMapper, new ObjectMapper());

        service.activatePolicy(scenario.newPolicyId());

        // Invariant: at most one active version exists per (scope, scope_id, personality)
        long activeCount = policyStore.values().stream()
                .filter(p -> STATUS_ACTIVE.equals(p.getStatus()))
                .filter(p -> p.getScope().equals(scenario.scope()))
                .filter(p -> Objects.equals(p.getScopeId(), scenario.scopeId()))
                .filter(p -> p.getPersonality().equals(scenario.personality()))
                .count();

        assertThat(activeCount)
                .as("At most one active version should exist per (scope=%s, scopeId=%s, personality=%s)",
                        scenario.scope(), scenario.scopeId(), scenario.personality())
                .isLessThanOrEqualTo(1);

        // The new policy should be active
        assertThat(newPolicy.getStatus())
                .as("The newly activated policy should have status=active")
                .isEqualTo(STATUS_ACTIVE);

        // The old policy should be inactive
        assertThat(existing.getStatus())
                .as("The previously active policy should have been deactivated")
                .isEqualTo(STATUS_INACTIVE);
    }

    // ================================================================================
    // Property 2: Most-specific resolution (Req 38.3)
    // ================================================================================

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 43: Personality policy single-active
     * and most-specific resolution.
     *
     * <p><b>Validates: Requirements 38.3</b>
     *
     * <p>Resolution returns the active policy at the most-specific scope level.
     * The precedence order is: campaign → goal → store → organization → system.
     */
    @Property(tries = MIN_ITERATIONS, generation = GenerationMode.RANDOMIZED)
    @Label("Most-specific resolution: returns the active policy at the most-specific scope")
    void resolutionReturnsMostSpecificActiveScope(
            @ForAll("resolutionScenarios") ResolutionScenario scenario) {

        PersonalityPolicyMapper mapper = mock(PersonalityPolicyMapper.class);
        AuditLogMapper auditLogMapper = mock(AuditLogMapper.class);

        UUID[] scopeIds = {
                scenario.campaignId(), scenario.goalId(),
                scenario.storeId(), scenario.orgId(), null
        };

        // Determine expected result: first active policy found walking precedence
        PersonalityPolicyEntity expectedResult = null;
        int expectedScopeIndex = -1;
        for (int i = 0; i < SCOPE_PRECEDENCE.size(); i++) {
            String scope = SCOPE_PRECEDENCE.get(i);
            UUID scopeId = scopeIds[i];
            if (scopeId == null && !"system".equals(scope)) continue;

            for (PolicyRow row : scenario.policies()) {
                if (row.scope().equals(scope)
                        && Objects.equals(row.scopeId(), scopeId)
                        && row.personality().equals(scenario.personality())
                        && STATUS_ACTIVE.equals(row.status())) {
                    expectedResult = buildPolicy(row.id(), row.scope(), row.scopeId(),
                            row.personality(), row.status(), row.ruleVersion());
                    expectedScopeIndex = i;
                    break;
                }
            }
            if (expectedResult != null) break;
        }

        // We only run this property for scenarios that have at least one active policy
        if (expectedResult == null) return;

        // Mock selectOne: stateful answer tracking which scope call we are on
        final int[] callCount = {0};
        final PersonalityPolicyEntity expected = expectedResult;
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenAnswer(inv -> {
            int idx = callCount[0]++;
            // Account for skipped scopes where scopeId is null
            int actualIdx = 0;
            for (int i = 0; i < SCOPE_PRECEDENCE.size() && actualIdx <= idx; i++) {
                String scope = SCOPE_PRECEDENCE.get(i);
                UUID scopeId = scopeIds[i];
                if (scopeId == null && !"system".equals(scope)) continue;

                if (actualIdx == idx) {
                    // This is the scope being queried — find a matching active policy
                    for (PolicyRow row : scenario.policies()) {
                        if (row.scope().equals(scope)
                                && Objects.equals(row.scopeId(), scopeId)
                                && row.personality().equals(scenario.personality())
                                && STATUS_ACTIVE.equals(row.status())) {
                            return buildPolicy(row.id(), row.scope(), row.scopeId(),
                                    row.personality(), row.status(), row.ruleVersion());
                        }
                    }
                    return null;
                }
                actualIdx++;
            }
            return null;
        });

        PersonalityPolicyScopeServiceImpl service = new PersonalityPolicyScopeServiceImpl(
                mapper, auditLogMapper, new ObjectMapper());

        PersonalityPolicyEntity result = service.resolvePolicy(
                scenario.personality(),
                scenario.campaignId(), scenario.goalId(),
                scenario.storeId(), scenario.orgId());

        assertThat(result.getId())
                .as("Resolution should return the most-specific active policy (scope=%s)",
                        expected.getScope())
                .isEqualTo(expected.getId());

        assertThat(result.getScope())
                .as("The returned policy should be from the most-specific scope with an active policy")
                .isEqualTo(expected.getScope());
    }

    // ================================================================================
    // Property 3: More-specific overrides less-specific (Req 38.3)
    // ================================================================================

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 43: Personality policy single-active
     * and most-specific resolution.
     *
     * <p><b>Validates: Requirements 38.2, 38.3</b>
     *
     * <p>When active policies exist at multiple scope levels, the more-specific scope
     * always wins over less-specific scopes.
     */
    @Property(tries = MIN_ITERATIONS, generation = GenerationMode.RANDOMIZED)
    @Label("Override: more-specific scope always wins over less-specific scope")
    void moreSpecificScopeOverridesLessSpecific(
            @ForAll("overrideScenarios") OverrideScenario scenario) {

        PersonalityPolicyMapper mapper = mock(PersonalityPolicyMapper.class);
        AuditLogMapper auditLogMapper = mock(AuditLogMapper.class);

        UUID[] scopeIds = {
                scenario.campaignId(), scenario.goalId(),
                scenario.storeId(), scenario.orgId(), null
        };

        // Stateful answer: return matching policy for the queried scope
        final int[] callCount = {0};
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenAnswer(inv -> {
            int idx = callCount[0]++;
            int actualIdx = 0;
            for (int i = 0; i < SCOPE_PRECEDENCE.size() && actualIdx <= idx; i++) {
                String scope = SCOPE_PRECEDENCE.get(i);
                UUID scopeId = scopeIds[i];
                if (scopeId == null && !"system".equals(scope)) continue;

                if (actualIdx == idx) {
                    if (scope.equals(scenario.moreSpecificScope())
                            && Objects.equals(scopeId, scenario.moreSpecificScopeId())) {
                        return buildPolicy(scenario.moreSpecificPolicyId(), scope, scopeId,
                                scenario.personality(), STATUS_ACTIVE, "v-specific");
                    }
                    if (scope.equals(scenario.lessSpecificScope())
                            && Objects.equals(scopeId, scenario.lessSpecificScopeId())) {
                        return buildPolicy(scenario.lessSpecificPolicyId(), scope, scopeId,
                                scenario.personality(), STATUS_ACTIVE, "v-general");
                    }
                    return null;
                }
                actualIdx++;
            }
            return null;
        });

        PersonalityPolicyScopeServiceImpl service = new PersonalityPolicyScopeServiceImpl(
                mapper, auditLogMapper, new ObjectMapper());

        PersonalityPolicyEntity result = service.resolvePolicy(
                scenario.personality(),
                scenario.campaignId(), scenario.goalId(),
                scenario.storeId(), scenario.orgId());

        // The more-specific policy must always be returned
        assertThat(result.getId())
                .as("More-specific scope '%s' should override less-specific scope '%s'",
                        scenario.moreSpecificScope(), scenario.lessSpecificScope())
                .isEqualTo(scenario.moreSpecificPolicyId());
    }

    // ================================================================================
    // Property 4: No active policy → exception (Req 38.3)
    // ================================================================================

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 43: Personality policy single-active
     * and most-specific resolution.
     *
     * <p><b>Validates: Requirements 38.2, 38.3</b>
     *
     * <p>When no scope has an active policy for the personality (and no system fallback
     * exists), resolution throws a BusinessException.
     */
    @Property(tries = MIN_ITERATIONS, generation = GenerationMode.RANDOMIZED)
    @Label("No active policy at any scope: resolution throws BusinessException")
    void noActivePolicyThrowsException(
            @ForAll("noPolicyScenarios") NoPolicyScenario scenario) {

        PersonalityPolicyMapper mapper = mock(PersonalityPolicyMapper.class);
        AuditLogMapper auditLogMapper = mock(AuditLogMapper.class);

        // No policy found at any scope
        when(mapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        PersonalityPolicyScopeServiceImpl service = new PersonalityPolicyScopeServiceImpl(
                mapper, auditLogMapper, new ObjectMapper());

        assertThatThrownBy(() -> service.resolvePolicy(
                scenario.personality(),
                scenario.campaignId(), scenario.goalId(),
                scenario.storeId(), scenario.orgId()))
                .isInstanceOf(com.adpilot.common.exception.BusinessException.class)
                .hasMessageContaining("No active Personality_Policy configured");
    }

    // ================================================================================
    // Records for scenarios
    // ================================================================================

    record ActivationScenario(
            UUID existingId,
            UUID newPolicyId,
            String scope,
            UUID scopeId,
            String personality
    ) {}

    record ResolutionScenario(
            String personality,
            UUID campaignId,
            UUID goalId,
            UUID storeId,
            UUID orgId,
            List<PolicyRow> policies
    ) {}

    record PolicyRow(
            UUID id,
            String scope,
            UUID scopeId,
            String personality,
            String status,
            String ruleVersion
    ) {}

    record OverrideScenario(
            String personality,
            UUID campaignId,
            UUID goalId,
            UUID storeId,
            UUID orgId,
            String moreSpecificScope,
            UUID moreSpecificScopeId,
            UUID moreSpecificPolicyId,
            String lessSpecificScope,
            UUID lessSpecificScopeId,
            UUID lessSpecificPolicyId
    ) {}

    record NoPolicyScenario(
            String personality,
            UUID campaignId,
            UUID goalId,
            UUID storeId,
            UUID orgId
    ) {}

    // ================================================================================
    // Generators
    // ================================================================================

    @Provide
    Arbitrary<ActivationScenario> activationScenarios() {
        Arbitrary<UUID> uuids = Arbitraries.create(UUID::randomUUID);
        Arbitrary<String> scopes = Arbitraries.of("campaign", "goal", "store", "organization", "system");
        Arbitrary<String> personalities = Arbitraries.of("conservative", "balanced", "aggressive");

        return Combinators.combine(uuids, uuids, scopes, uuids, personalities)
                .as((existingId, newId, scope, scopeId, personality) ->
                        new ActivationScenario(
                                existingId, newId, scope,
                                "system".equals(scope) ? null : scopeId,
                                personality));
    }

    @Provide
    Arbitrary<ResolutionScenario> resolutionScenarios() {
        Arbitrary<UUID> uuids = Arbitraries.create(UUID::randomUUID);
        Arbitrary<String> personalities = Arbitraries.of("conservative", "balanced", "aggressive");

        return Combinators.combine(personalities, uuids, uuids, uuids, uuids)
                .flatAs((personality, campaignId, goalId, storeId, orgId) -> {
                    // Pick a random scope to place an active policy
                    Arbitrary<Integer> scopeIndex = Arbitraries.integers().between(0, 4);
                    return scopeIndex.map(idx -> {
                        String scope = SCOPE_PRECEDENCE.get(idx);
                        UUID[] ids = { campaignId, goalId, storeId, orgId, null };
                        UUID scopeId = ids[idx];

                        List<PolicyRow> policies = new ArrayList<>();
                        policies.add(new PolicyRow(
                                UUID.randomUUID(), scope, scopeId, personality,
                                STATUS_ACTIVE, "v1"));

                        // Optionally add less-specific active policies (which should be ignored)
                        for (int j = idx + 1; j < SCOPE_PRECEDENCE.size(); j++) {
                            String lowerScope = SCOPE_PRECEDENCE.get(j);
                            UUID lowerScopeId = ids[j];
                            if (lowerScopeId == null && !"system".equals(lowerScope)) continue;
                            policies.add(new PolicyRow(
                                    UUID.randomUUID(), lowerScope, lowerScopeId, personality,
                                    STATUS_ACTIVE, "v-lower-" + j));
                        }

                        return new ResolutionScenario(personality, campaignId, goalId,
                                storeId, orgId, policies);
                    });
                });
    }

    @Provide
    Arbitrary<OverrideScenario> overrideScenarios() {
        Arbitrary<UUID> uuids = Arbitraries.create(UUID::randomUUID);
        Arbitrary<String> personalities = Arbitraries.of("conservative", "balanced", "aggressive");

        // Generate a pair of scope indices where moreIdx < lessIdx (more-specific first)
        Arbitrary<int[]> scopePairs = Arbitraries.integers().between(0, 3)
                .flatMap(moreIdx -> Arbitraries.integers().between(moreIdx + 1, 4)
                        .map(lessIdx -> new int[]{moreIdx, lessIdx}));

        return Combinators.combine(personalities, uuids, uuids, uuids, uuids, scopePairs, uuids, uuids)
                .as((personality, campaignId, goalId, storeId, orgId, pair, policyId1, policyId2) -> {
                    int moreIdx = pair[0];
                    int lessIdx = pair[1];

                    String moreScope = SCOPE_PRECEDENCE.get(moreIdx);
                    String lessScope = SCOPE_PRECEDENCE.get(lessIdx);

                    UUID[] ids = { campaignId, goalId, storeId, orgId, null };
                    UUID moreScopeId = ids[moreIdx];
                    UUID lessScopeId = ids[lessIdx];

                    return new OverrideScenario(
                            personality, campaignId, goalId, storeId, orgId,
                            moreScope, moreScopeId, policyId1,
                            lessScope, lessScopeId, policyId2);
                });
    }

    @Provide
    Arbitrary<NoPolicyScenario> noPolicyScenarios() {
        Arbitrary<UUID> uuids = Arbitraries.create(UUID::randomUUID);
        Arbitrary<String> personalities = Arbitraries.of("conservative", "balanced", "aggressive");

        return Combinators.combine(personalities, uuids, uuids, uuids, uuids)
                .as(NoPolicyScenario::new);
    }

    // ================================================================================
    // Helpers
    // ================================================================================

    private static PersonalityPolicyEntity buildPolicy(UUID id, String scope, UUID scopeId,
                                                       String personality, String status,
                                                       String ruleVersion) {
        return PersonalityPolicyEntity.builder()
                .id(id)
                .scope(scope)
                .scopeId(scopeId)
                .personality(personality)
                .status(status)
                .ruleVersion(ruleVersion)
                .build();
    }
}
