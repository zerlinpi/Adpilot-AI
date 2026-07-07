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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link HostingOrgIsolationGuard}.
 *
 * <p>Covers the three core cross-org isolation cases for every inbound id type:
 * same-org access is allowed, cross-org access is denied with HTTP 403 (no
 * cross-org data leaked), and a non-existent id yields HTTP 404. The guard runs
 * BEFORE data-scope and is layered in addition to it (Requirement 39.1/39.3/39.5).</p>
 *
 * <p><b>Validates: Requirements 39.1, 39.2, 39.3, 39.4, 39.5</b></p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("HostingOrgIsolationGuard — cross-organization isolation")
class HostingOrgIsolationGuardTest {

    @Mock
    private StoreMapper storeMapper;
    @Mock
    private CampaignMapper campaignMapper;
    @Mock
    private GoalMapper goalMapper;
    @Mock
    private OperationMapper operationMapper;
    @Mock
    private OptimizationRunMapper optimizationRunMapper;
    @Mock
    private AiDecisionMapper aiDecisionMapper;

    private HostingOrgIsolationGuard guard;

    private static final UUID CALLER_ORG = UUID.randomUUID();
    private static final UUID OTHER_ORG = UUID.randomUUID();

    private static final UUID SAME_ORG_STORE = UUID.randomUUID();
    private static final UUID CROSS_ORG_STORE = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        guard = new HostingOrgIsolationGuard(
                storeMapper, campaignMapper, goalMapper,
                operationMapper, optimizationRunMapper, aiDecisionMapper);
        authenticateAs(CALLER_ORG);

        // Two stores: one in the caller's org, one in another org. Tests reference
        // whichever they need; lenient() so unused stubs do not fail strict mocking.
        lenient().when(storeMapper.selectById(SAME_ORG_STORE))
                .thenReturn(store(SAME_ORG_STORE, CALLER_ORG));
        lenient().when(storeMapper.selectById(CROSS_ORG_STORE))
                .thenReturn(store(CROSS_ORG_STORE, OTHER_ORG));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------------
    // storeId
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("resolveStoreInCallerOrg")
    class StoreResolution {

        @Test
        @DisplayName("allows a store in the caller's organization")
        void allowsSameOrg() {
            StoreEntity resolved = guard.resolveStoreInCallerOrg(SAME_ORG_STORE);

            assertThat(resolved.getId()).isEqualTo(SAME_ORG_STORE);
            assertThat(resolved.getOrgId()).isEqualTo(CALLER_ORG);
        }

        @Test
        @DisplayName("denies a store in another organization with 403 and no resource detail")
        void deniesCrossOrg() {
            assertThatThrownBy(() -> guard.resolveStoreInCallerOrg(CROSS_ORG_STORE))
                    .isInstanceOfSatisfying(BusinessException.class, ex -> {
                        assertThat(ex.getStatus()).isEqualTo(403);
                        assertThat(ex.getMessage()).doesNotContain(CROSS_ORG_STORE.toString());
                        assertThat(ex.getMessage()).doesNotContain(OTHER_ORG.toString());
                    });
        }

        @Test
        @DisplayName("returns 404 for a non-existent store id")
        void notFoundForMissing() {
            UUID missing = UUID.randomUUID();
            when(storeMapper.selectById(missing)).thenReturn(null);

            assertThatThrownBy(() -> guard.resolveStoreInCallerOrg(missing))
                    .isInstanceOfSatisfying(BusinessException.class,
                            ex -> assertThat(ex.getStatus()).isEqualTo(404));
        }

        @Test
        @DisplayName("returns 404 for a null store id")
        void notFoundForNull() {
            assertThatThrownBy(() -> guard.resolveStoreInCallerOrg(null))
                    .isInstanceOfSatisfying(BusinessException.class,
                            ex -> assertThat(ex.getStatus()).isEqualTo(404));
        }

        @Test
        @DisplayName("denies a store with no org_id (cannot belong to any caller)")
        void deniesOrglessStore() {
            UUID orgless = UUID.randomUUID();
            when(storeMapper.selectById(orgless)).thenReturn(store(orgless, null));

            assertThatThrownBy(() -> guard.resolveStoreInCallerOrg(orgless))
                    .isInstanceOfSatisfying(BusinessException.class,
                            ex -> assertThat(ex.getStatus()).isEqualTo(403));
        }
    }

    // ------------------------------------------------------------------
    // campaignId
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("resolveCampaignInCallerOrg")
    class CampaignResolution {

        @Test
        @DisplayName("allows a campaign whose store is in the caller's org")
        void allowsSameOrg() {
            UUID campaignId = UUID.randomUUID();
            when(campaignMapper.selectById(campaignId)).thenReturn(campaign(campaignId, SAME_ORG_STORE));

            CampaignEntity resolved = guard.resolveCampaignInCallerOrg(campaignId);

            assertThat(resolved.getId()).isEqualTo(campaignId);
            assertThat(resolved.getStoreId()).isEqualTo(SAME_ORG_STORE);
        }

        @Test
        @DisplayName("denies a campaign whose store is in another org with 403")
        void deniesCrossOrg() {
            UUID campaignId = UUID.randomUUID();
            when(campaignMapper.selectById(campaignId)).thenReturn(campaign(campaignId, CROSS_ORG_STORE));

            assertThatThrownBy(() -> guard.resolveCampaignInCallerOrg(campaignId))
                    .isInstanceOfSatisfying(BusinessException.class,
                            ex -> assertThat(ex.getStatus()).isEqualTo(403));
        }

        @Test
        @DisplayName("returns 404 for a non-existent campaign id")
        void notFoundForMissing() {
            UUID missing = UUID.randomUUID();
            when(campaignMapper.selectById(missing)).thenReturn(null);

            assertThatThrownBy(() -> guard.resolveCampaignInCallerOrg(missing))
                    .isInstanceOfSatisfying(BusinessException.class,
                            ex -> assertThat(ex.getStatus()).isEqualTo(404));
        }
    }

    // ------------------------------------------------------------------
    // goalId
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("resolveGoalInCallerOrg")
    class GoalResolution {

        @Test
        @DisplayName("allows a goal whose store is in the caller's org")
        void allowsSameOrg() {
            UUID goalId = UUID.randomUUID();
            when(goalMapper.selectById(goalId)).thenReturn(goal(goalId, SAME_ORG_STORE));

            GoalEntity resolved = guard.resolveGoalInCallerOrg(goalId);

            assertThat(resolved.getId()).isEqualTo(goalId);
        }

        @Test
        @DisplayName("denies a goal whose store is in another org with 403")
        void deniesCrossOrg() {
            UUID goalId = UUID.randomUUID();
            when(goalMapper.selectById(goalId)).thenReturn(goal(goalId, CROSS_ORG_STORE));

            assertThatThrownBy(() -> guard.resolveGoalInCallerOrg(goalId))
                    .isInstanceOfSatisfying(BusinessException.class,
                            ex -> assertThat(ex.getStatus()).isEqualTo(403));
        }

        @Test
        @DisplayName("returns 404 for a non-existent goal id")
        void notFoundForMissing() {
            UUID missing = UUID.randomUUID();
            when(goalMapper.selectById(missing)).thenReturn(null);

            assertThatThrownBy(() -> guard.resolveGoalInCallerOrg(missing))
                    .isInstanceOfSatisfying(BusinessException.class,
                            ex -> assertThat(ex.getStatus()).isEqualTo(404));
        }
    }

    // ------------------------------------------------------------------
    // operationId
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("resolveOperationInCallerOrg")
    class OperationResolution {

        @Test
        @DisplayName("allows an operation whose store is in the caller's org")
        void allowsSameOrg() {
            UUID operationId = UUID.randomUUID();
            when(operationMapper.selectById(operationId)).thenReturn(operation(operationId, SAME_ORG_STORE));

            OperationEntity resolved = guard.resolveOperationInCallerOrg(operationId);

            assertThat(resolved.getId()).isEqualTo(operationId);
        }

        @Test
        @DisplayName("denies an operation whose store is in another org with 403")
        void deniesCrossOrg() {
            UUID operationId = UUID.randomUUID();
            when(operationMapper.selectById(operationId)).thenReturn(operation(operationId, CROSS_ORG_STORE));

            assertThatThrownBy(() -> guard.resolveOperationInCallerOrg(operationId))
                    .isInstanceOfSatisfying(BusinessException.class,
                            ex -> assertThat(ex.getStatus()).isEqualTo(403));
        }

        @Test
        @DisplayName("returns 404 for a non-existent operation id")
        void notFoundForMissing() {
            UUID missing = UUID.randomUUID();
            when(operationMapper.selectById(missing)).thenReturn(null);

            assertThatThrownBy(() -> guard.resolveOperationInCallerOrg(missing))
                    .isInstanceOfSatisfying(BusinessException.class,
                            ex -> assertThat(ex.getStatus()).isEqualTo(404));
        }
    }

    // ------------------------------------------------------------------
    // runId
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("resolveRunInCallerOrg")
    class RunResolution {

        @Test
        @DisplayName("allows a run whose store is in the caller's org")
        void allowsSameOrg() {
            UUID runId = UUID.randomUUID();
            when(optimizationRunMapper.selectById(runId)).thenReturn(run(runId, SAME_ORG_STORE));

            OptimizationRunEntity resolved = guard.resolveRunInCallerOrg(runId);

            assertThat(resolved.getId()).isEqualTo(runId);
        }

        @Test
        @DisplayName("denies a run whose store is in another org with 403")
        void deniesCrossOrg() {
            UUID runId = UUID.randomUUID();
            when(optimizationRunMapper.selectById(runId)).thenReturn(run(runId, CROSS_ORG_STORE));

            assertThatThrownBy(() -> guard.resolveRunInCallerOrg(runId))
                    .isInstanceOfSatisfying(BusinessException.class,
                            ex -> assertThat(ex.getStatus()).isEqualTo(403));
        }

        @Test
        @DisplayName("returns 404 for a non-existent run id")
        void notFoundForMissing() {
            UUID missing = UUID.randomUUID();
            when(optimizationRunMapper.selectById(missing)).thenReturn(null);

            assertThatThrownBy(() -> guard.resolveRunInCallerOrg(missing))
                    .isInstanceOfSatisfying(BusinessException.class,
                            ex -> assertThat(ex.getStatus()).isEqualTo(404));
        }
    }

    // ------------------------------------------------------------------
    // decisionId
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("resolveDecisionInCallerOrg")
    class DecisionResolution {

        @Test
        @DisplayName("allows a decision whose store is in the caller's org")
        void allowsSameOrg() {
            UUID decisionId = UUID.randomUUID();
            when(aiDecisionMapper.selectById(decisionId)).thenReturn(decision(decisionId, SAME_ORG_STORE));

            AiDecisionEntity resolved = guard.resolveDecisionInCallerOrg(decisionId);

            assertThat(resolved.getId()).isEqualTo(decisionId);
        }

        @Test
        @DisplayName("denies a decision whose store is in another org with 403")
        void deniesCrossOrg() {
            UUID decisionId = UUID.randomUUID();
            when(aiDecisionMapper.selectById(decisionId)).thenReturn(decision(decisionId, CROSS_ORG_STORE));

            assertThatThrownBy(() -> guard.resolveDecisionInCallerOrg(decisionId))
                    .isInstanceOfSatisfying(BusinessException.class,
                            ex -> assertThat(ex.getStatus()).isEqualTo(403));
        }

        @Test
        @DisplayName("returns 404 for a non-existent decision id")
        void notFoundForMissing() {
            UUID missing = UUID.randomUUID();
            when(aiDecisionMapper.selectById(missing)).thenReturn(null);

            assertThatThrownBy(() -> guard.resolveDecisionInCallerOrg(missing))
                    .isInstanceOfSatisfying(BusinessException.class,
                            ex -> assertThat(ex.getStatus()).isEqualTo(404));
        }
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

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
}
