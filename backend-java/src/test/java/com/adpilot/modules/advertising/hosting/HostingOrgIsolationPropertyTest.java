package com.adpilot.modules.advertising.hosting;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.common.security.CurrentUser;
import com.adpilot.modules.advertising.entity.CampaignEntity;
import com.adpilot.modules.advertising.entity.GoalEntity;
import com.adpilot.modules.advertising.entity.OperationEntity;
import com.adpilot.modules.advertising.mapper.CampaignMapper;
import com.adpilot.modules.advertising.mapper.GoalMapper;
import com.adpilot.modules.advertising.mapper.OperationMapper;
import com.adpilot.modules.store.entity.StoreEntity;
import com.adpilot.modules.store.mapper.StoreMapper;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Assume;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.lifecycle.AfterTry;
import org.mockito.Mockito;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Property-based test for {@link HostingOrgIsolationGuard} cross-organization isolation.
 *
 * <p>Feature: amazon-ads-ai-hosting-system, Property 44: Cross-organization isolation.
 *
 * <p><b>Validates: Requirements 39.1, 39.3, 39.4, 39.5</b>
 *
 * <p>Every hosting id ({@code storeId}, {@code campaignId}, {@code goalId},
 * {@code operationId}, {@code runId}, {@code decisionId}) resolves to a store and the
 * guard asserts that store belongs to the caller's current organization. These
 * properties hold uniformly across <em>all</em> id types and arbitrary id/org values:
 * <ul>
 *   <li>same-org entities resolve and are returned (Req 39.1);</li>
 *   <li>cross-org entities are always denied with HTTP 403, regardless of id type
 *       (Req 39.1 / 39.3 / 39.5);</li>
 *   <li>the 403/404 messages never echo the resource id or the other org's id, so no
 *       cross-org data leaks (Req 39.1 / 39.4);</li>
 *   <li>a non-existent id always yields HTTP 404 (Req 39.4).</li>
 * </ul>
 */
class HostingOrgIsolationPropertyTest {

    /** The inbound id types the guard resolves; isolation must hold for every one. */
    enum EntityKind {
        STORE, CAMPAIGN, GOAL, OPERATION, RUN, DECISION
    }

    /** Bundle of freshly-mocked mappers plus the guard under test. */
    private record Harness(
            StoreMapper storeMapper,
            CampaignMapper campaignMapper,
            GoalMapper goalMapper,
            OperationMapper operationMapper,
            OptimizationRunMapper optimizationRunMapper,
            AiDecisionMapper aiDecisionMapper,
            HostingOrgIsolationGuard guard) {

        static Harness fresh() {
            StoreMapper storeMapper = Mockito.mock(StoreMapper.class);
            CampaignMapper campaignMapper = Mockito.mock(CampaignMapper.class);
            GoalMapper goalMapper = Mockito.mock(GoalMapper.class);
            OperationMapper operationMapper = Mockito.mock(OperationMapper.class);
            OptimizationRunMapper optimizationRunMapper = Mockito.mock(OptimizationRunMapper.class);
            AiDecisionMapper aiDecisionMapper = Mockito.mock(AiDecisionMapper.class);
            HostingOrgIsolationGuard guard = new HostingOrgIsolationGuard(
                    storeMapper, campaignMapper, goalMapper,
                    operationMapper, optimizationRunMapper, aiDecisionMapper);
            return new Harness(storeMapper, campaignMapper, goalMapper,
                    operationMapper, optimizationRunMapper, aiDecisionMapper, guard);
        }
    }

    @AfterTry
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------------
    // Property 44.1 — same-org resolution succeeds for every id type
    // ------------------------------------------------------------------

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 44: Cross-organization isolation.
     *
     * <p><b>Validates: Requirements 39.1, 39.3, 39.5</b>
     *
     * <p>For any id type and any entity whose backing store belongs to the caller's
     * organization, resolution succeeds and returns the resolved entity.
     */
    @Property(tries = 300)
    void sameOrgResolutionSucceedsForEveryIdType(
            @ForAll EntityKind kind,
            @ForAll("uuids") UUID entityId,
            @ForAll("uuids") UUID storeId,
            @ForAll("uuids") UUID callerOrg) {

        Harness h = Harness.fresh();
        authenticateAs(callerOrg);
        UUID expectedId = registerFoundEntity(h, kind, entityId, storeId, callerOrg);

        Object resolved = resolve(h.guard(), kind, expectedId);

        assertThat(resolved).isNotNull();
        assertThat(extractId(resolved)).isEqualTo(expectedId);
    }

    // ------------------------------------------------------------------
    // Property 44.2 / 44.3 — cross-org always 403 with no data leak
    // ------------------------------------------------------------------

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 44: Cross-organization isolation.
     *
     * <p><b>Validates: Requirements 39.1, 39.3, 39.4, 39.5</b>
     *
     * <p>For any id type, an entity whose backing store belongs to a DIFFERENT
     * organization is always denied with HTTP 403, and the error message never
     * contains the resource id, the store id, or the other org's id.
     */
    @Property(tries = 300)
    void crossOrgResolutionIsAlwaysForbiddenAndLeaksNothing(
            @ForAll EntityKind kind,
            @ForAll("uuids") UUID entityId,
            @ForAll("uuids") UUID storeId,
            @ForAll("uuids") UUID callerOrg,
            @ForAll("uuids") UUID otherOrg) {

        Assume.that(!callerOrg.equals(otherOrg));

        Harness h = Harness.fresh();
        authenticateAs(callerOrg);
        UUID requestedId = registerFoundEntity(h, kind, entityId, storeId, otherOrg);

        assertThatThrownBy(() -> resolve(h.guard(), kind, requestedId))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(403);
                    String message = String.valueOf(ex.getMessage());
                    assertThat(message).doesNotContain(requestedId.toString());
                    assertThat(message).doesNotContain(storeId.toString());
                    assertThat(message).doesNotContain(otherOrg.toString());
                });
    }

    // ------------------------------------------------------------------
    // Property 44.4 — non-existent id always 404 for every id type
    // ------------------------------------------------------------------

    /**
     * Feature: amazon-ads-ai-hosting-system, Property 44: Cross-organization isolation.
     *
     * <p><b>Validates: Requirements 39.1, 39.4</b>
     *
     * <p>For any id type, a non-existent id always yields HTTP 404 and the message
     * never echoes the requested id.
     */
    @Property(tries = 300)
    void nonExistentIdAlwaysYields404ForEveryIdType(
            @ForAll EntityKind kind,
            @ForAll("uuids") UUID missingId,
            @ForAll("uuids") UUID callerOrg) {

        Harness h = Harness.fresh();
        authenticateAs(callerOrg);
        // No stubbing: every mapper returns null for unknown ids, so the entity
        // (or, for STORE, the store itself) is treated as not found.

        assertThatThrownBy(() -> resolve(h.guard(), kind, missingId))
                .isInstanceOfSatisfying(BusinessException.class, ex -> {
                    assertThat(ex.getStatus()).isEqualTo(404);
                    assertThat(String.valueOf(ex.getMessage())).doesNotContain(missingId.toString());
                });
    }

    // ------------------------------------------------------------------
    // Resolution dispatch + fixtures
    // ------------------------------------------------------------------

    /** Invoke the guard method that corresponds to the given id type. */
    private static Object resolve(HostingOrgIsolationGuard guard, EntityKind kind, UUID id) {
        return switch (kind) {
            case STORE -> guard.resolveStoreInCallerOrg(id);
            case CAMPAIGN -> guard.resolveCampaignInCallerOrg(id);
            case GOAL -> guard.resolveGoalInCallerOrg(id);
            case OPERATION -> guard.resolveOperationInCallerOrg(id);
            case RUN -> guard.resolveRunInCallerOrg(id);
            case DECISION -> guard.resolveDecisionInCallerOrg(id);
        };
    }

    /**
     * Stub the mappers so the requested entity exists and its backing store belongs to
     * {@code storeOrg}. For {@link EntityKind#STORE} the entity <em>is</em> the store, so
     * the entity id and the store id coincide.
     *
     * @return the id that should be passed to {@link #resolve}
     */
    private static UUID registerFoundEntity(
            Harness h, EntityKind kind, UUID entityId, UUID storeId, UUID storeOrg) {

        if (kind == EntityKind.STORE) {
            when(h.storeMapper().selectById(entityId)).thenReturn(store(entityId, storeOrg));
            return entityId;
        }

        when(h.storeMapper().selectById(storeId)).thenReturn(store(storeId, storeOrg));
        switch (kind) {
            case CAMPAIGN -> when(h.campaignMapper().selectById(entityId))
                    .thenReturn(campaign(entityId, storeId));
            case GOAL -> when(h.goalMapper().selectById(entityId))
                    .thenReturn(goal(entityId, storeId));
            case OPERATION -> when(h.operationMapper().selectById(entityId))
                    .thenReturn(operation(entityId, storeId));
            case RUN -> when(h.optimizationRunMapper().selectById(entityId))
                    .thenReturn(run(entityId, storeId));
            case DECISION -> when(h.aiDecisionMapper().selectById(entityId))
                    .thenReturn(decision(entityId, storeId));
            default -> throw new IllegalStateException("unhandled kind " + kind);
        }
        return entityId;
    }

    private static UUID extractId(Object entity) {
        if (entity instanceof StoreEntity s) return s.getId();
        if (entity instanceof CampaignEntity c) return c.getId();
        if (entity instanceof GoalEntity g) return g.getId();
        if (entity instanceof OperationEntity o) return o.getId();
        if (entity instanceof OptimizationRunEntity r) return r.getId();
        if (entity instanceof AiDecisionEntity d) return d.getId();
        throw new IllegalArgumentException("unexpected entity type: " + entity);
    }

    private static void authenticateAs(UUID orgId) {
        CurrentUser principal = CurrentUser.builder()
                .userId(UUID.randomUUID().toString())
                .orgId(orgId != null ? orgId.toString() : null)
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    private static StoreEntity store(UUID id, UUID orgId) {
        StoreEntity s = new StoreEntity();
        s.setId(id);
        s.setOrgId(orgId);
        return s;
    }

    private static CampaignEntity campaign(UUID id, UUID storeId) {
        return CampaignEntity.builder().id(id).storeId(storeId).build();
    }

    private static GoalEntity goal(UUID id, UUID storeId) {
        return GoalEntity.builder().id(id).storeId(storeId).build();
    }

    private static OperationEntity operation(UUID id, UUID storeId) {
        return OperationEntity.builder().id(id).storeId(storeId).build();
    }

    private static OptimizationRunEntity run(UUID id, UUID storeId) {
        return OptimizationRunEntity.builder().id(id).storeId(storeId).build();
    }

    private static AiDecisionEntity decision(UUID id, UUID storeId) {
        return AiDecisionEntity.builder().id(id).storeId(storeId).build();
    }

    // ------------------------------------------------------------------
    // Generators
    // ------------------------------------------------------------------

    /** Random UUIDs covering the full inbound id space. */
    @Provide
    Arbitrary<UUID> uuids() {
        return Arbitraries.randomValue(r -> UUID.randomUUID());
    }
}
