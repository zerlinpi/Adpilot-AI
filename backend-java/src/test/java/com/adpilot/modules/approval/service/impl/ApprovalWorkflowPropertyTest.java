package com.adpilot.modules.approval.service.impl;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.common.security.CurrentUser;
import com.adpilot.modules.approval.annotation.RequiresApproval;
import com.adpilot.modules.approval.aspect.ApprovalAspect;
import com.adpilot.modules.approval.entity.ApprovalDecisionEntity;
import com.adpilot.modules.approval.entity.ApprovalPolicyEntity;
import com.adpilot.modules.approval.entity.ApprovalRequestEntity;
import com.adpilot.modules.approval.mapper.ApprovalDecisionMapper;
import com.adpilot.modules.approval.mapper.ApprovalPolicyMapper;
import com.adpilot.modules.approval.mapper.ApprovalRequestMapper;
import com.adpilot.modules.approval.service.ApprovalActionExecutor;
import com.adpilot.modules.audit.service.AuditLogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.aspectj.lang.ProceedingJoinPoint;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Property-based test for the policy-driven approval workflow
 * ({@link ApprovalWorkflowServiceImpl}) and the gating aspect
 * ({@link ApprovalAspect}).
 *
 * Feature: core-platform-completion, Property 25: Approval gating, completion,
 * rejection, ordering, and self-approval prohibition.
 *
 * <p><em>For any</em> governed action and policy, the action is placed in a
 * pending state instead of executing when the policy is enabled and its
 * threshold is met (and executes immediately otherwise); the action executes
 * exactly once only after all required levels approve in their defined order;
 * any rejection cancels the action without executing it; an approval at the
 * wrong level is prohibited; and the user who initiated the action can never
 * record an approval for it.</p>
 *
 * <p>The MyBatis mappers ({@link ApprovalRequestMapper},
 * {@link ApprovalDecisionMapper}, {@link ApprovalPolicyMapper}) are mocked with
 * stateful in-memory backing so the multi-step workflow is exercised end to end
 * without a database, and a recording {@link ApprovalActionExecutor} counts how
 * many times the gated action is carried out. Policies are {@code multi_level}
 * so each listed approver is one ordered level (Req 12.1.5).</p>
 *
 * Validates: Requirements 12.1.1, 12.1.3, 12.1.4, 12.1.5, 12.1.6
 */
class ApprovalWorkflowPropertyTest {

    private static final String MODULE = "advertising";
    private static final String ACTION = "bid_change";

    // ------------------------------------------------------------------
    // Property 25 (Req 12.1.3, 12.1.5): completion in defined order, exactly once.
    // ------------------------------------------------------------------

    // Feature: core-platform-completion, Property 25: Approval gating, completion, rejection, ordering, and self-approval prohibition
    @Property(tries = 200)
    void allLevelsApprovedInOrderExecuteTheActionExactlyOnce(@ForAll("scenarios") Scenario scenario) {
        Fixture fixture = new Fixture(scenario);
        UUID requestId = fixture.createPendingRequest();
        int levels = scenario.levelApprovers.size();

        for (int i = 0; i < levels; i++) {
            // Before the action completes it stays pending and has never executed.
            assertThat(fixture.status(requestId)).isEqualTo("pending");
            assertThat(fixture.executionCount(requestId)).isZero();
            // Current level advances strictly in the policy's defined order.
            assertThat(fixture.currentLevel(requestId)).isEqualTo(i + 1);

            fixture.service.approve(requestId, scenario.levelApprovers.get(i), "ok");

            if (i < levels - 1) {
                // More levels remain: still pending, advanced to the next level, not executed.
                assertThat(fixture.status(requestId)).isEqualTo("pending");
                assertThat(fixture.currentLevel(requestId)).isEqualTo(i + 2);
                assertThat(fixture.executionCount(requestId)).isZero();
            }
        }

        // Final level approved: completed and the gated action ran exactly once (Req 12.1.3).
        assertThat(fixture.status(requestId)).isEqualTo("approved");
        assertThat(fixture.executionCount(requestId)).isEqualTo(1);
        // One recorded decision per level, all "approved".
        assertThat(fixture.decisionsFor(requestId)).hasSize(levels);
        assertThat(fixture.decisionsFor(requestId))
                .allMatch(d -> "approved".equals(d.getDecision()));

        // Exactly once: any further decision on the now-terminal request is refused
        // and never triggers a second execution.
        assertThatThrownBy(() -> fixture.service.approve(requestId, scenario.levelApprovers.get(0), "again"))
                .isInstanceOf(BusinessException.class);
        assertThat(fixture.executionCount(requestId)).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // Property 25 (Req 12.1.5): an approval at the wrong level is prohibited.
    // ------------------------------------------------------------------

    // Feature: core-platform-completion, Property 25: Approval gating, completion, rejection, ordering, and self-approval prohibition
    @Property(tries = 200)
    void approvalByAnApproverOfADifferentLevelIsRejected(
            @ForAll("multiLevelScenarios") Scenario scenario,
            @ForAll int rawTargetLevel,
            @ForAll int rawWrongIndex) {

        Fixture fixture = new Fixture(scenario);
        UUID requestId = fixture.createPendingRequest();
        int levels = scenario.levelApprovers.size();

        // Advance correctly to a current level k (1-based) by approving levels 1..k-1.
        int k = Math.floorMod(rawTargetLevel, levels) + 1;
        for (int i = 0; i < k - 1; i++) {
            fixture.service.approve(requestId, scenario.levelApprovers.get(i), null);
        }
        assertThat(fixture.currentLevel(requestId)).isEqualTo(k);
        assertThat(fixture.status(requestId)).isEqualTo("pending");

        // Pick an approver that belongs to a DIFFERENT level than the current one.
        int currentIdx = k - 1;
        int wrongIdx = Math.floorMod(rawWrongIndex, levels);
        if (wrongIdx == currentIdx) {
            wrongIdx = (wrongIdx + 1) % levels;
        }
        UUID wrongLevelApprover = scenario.levelApprovers.get(wrongIdx);

        // Out-of-order approval is prohibited: only the current level's approver may act.
        assertThatThrownBy(() -> fixture.service.approve(requestId, wrongLevelApprover, "nope"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo("APPROVAL_NOT_AUTHORIZED");
        // A wrong-level reject is equally refused.
        assertThatThrownBy(() -> fixture.service.reject(requestId, wrongLevelApprover, "nope"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo("APPROVAL_NOT_AUTHORIZED");

        // The request is unaffected and the action still has not executed.
        assertThat(fixture.status(requestId)).isEqualTo("pending");
        assertThat(fixture.currentLevel(requestId)).isEqualTo(k);
        assertThat(fixture.executionCount(requestId)).isZero();
    }

    // ------------------------------------------------------------------
    // Property 25 (Req 12.1.4): a rejection at any level cancels without executing.
    // ------------------------------------------------------------------

    // Feature: core-platform-completion, Property 25: Approval gating, completion, rejection, ordering, and self-approval prohibition
    @Property(tries = 200)
    void aRejectionAtAnyLevelCancelsTheActionWithoutExecuting(
            @ForAll("scenarios") Scenario scenario,
            @ForAll int rawRejectLevel) {

        Fixture fixture = new Fixture(scenario);
        UUID requestId = fixture.createPendingRequest();
        int levels = scenario.levelApprovers.size();

        // Advance correctly to a current level k, then reject there.
        int k = Math.floorMod(rawRejectLevel, levels) + 1;
        for (int i = 0; i < k - 1; i++) {
            fixture.service.approve(requestId, scenario.levelApprovers.get(i), null);
        }
        assertThat(fixture.status(requestId)).isEqualTo("pending");
        assertThat(fixture.executionCount(requestId)).isZero();

        fixture.service.reject(requestId, scenario.levelApprovers.get(k - 1), "denied");

        // Rejection is terminal and the gated action never runs (Req 12.1.4).
        assertThat(fixture.status(requestId)).isEqualTo("rejected");
        assertThat(fixture.executionCount(requestId)).isZero();
        assertThat(fixture.request(requestId).getRejectionReason()).isEqualTo("denied");

        // The terminal request cannot be approved or rejected again, and still never executes.
        assertThatThrownBy(() -> fixture.service.approve(requestId, scenario.levelApprovers.get(k - 1), "x"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> fixture.service.reject(requestId, scenario.levelApprovers.get(k - 1), "x"))
                .isInstanceOf(BusinessException.class);
        assertThat(fixture.executionCount(requestId)).isZero();
    }

    // ------------------------------------------------------------------
    // Property 25 (Req 12.1.6): the initiator can never approve their own action.
    // ------------------------------------------------------------------

    // Feature: core-platform-completion, Property 25: Approval gating, completion, rejection, ordering, and self-approval prohibition
    @Property(tries = 200)
    void theInitiatorCanNeverApproveOrRejectTheirOwnAction(
            @ForAll("scenarios") Scenario scenario,
            @ForAll int rawLevel) {

        Fixture fixture = new Fixture(scenario);
        UUID requestId = fixture.createPendingRequest();
        int levels = scenario.levelApprovers.size();

        // Advance to an arbitrary current level so the prohibition holds at every level.
        int k = Math.floorMod(rawLevel, levels) + 1;
        for (int i = 0; i < k - 1; i++) {
            fixture.service.approve(requestId, scenario.levelApprovers.get(i), null);
        }

        // Self-approval and self-rejection by the initiator are both prohibited (Req 12.1.6).
        assertThatThrownBy(() -> fixture.service.approve(requestId, scenario.initiator, "self"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo("APPROVAL_SELF_FORBIDDEN");
        assertThatThrownBy(() -> fixture.service.reject(requestId, scenario.initiator, "self"))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo("APPROVAL_SELF_FORBIDDEN");

        // The request remains pending at the same level and never executes.
        assertThat(fixture.status(requestId)).isEqualTo("pending");
        assertThat(fixture.currentLevel(requestId)).isEqualTo(k);
        assertThat(fixture.executionCount(requestId)).isZero();
    }

    // ------------------------------------------------------------------
    // Property 25 (Req 12.1.1): a governed action is gated into pending instead
    // of executing when an enabled policy applies, and executes immediately
    // otherwise.
    // ------------------------------------------------------------------

    // Feature: core-platform-completion, Property 25: Approval gating, completion, rejection, ordering, and self-approval prohibition
    @Property(tries = 200)
    void anEnabledPolicyGatesTheActionIntoPendingOtherwiseItExecutes(
            @ForAll("scenarios") Scenario scenario,
            @ForAll boolean policyApplies) throws Throwable {

        UUID orgId = UUID.randomUUID();
        UUID userId = scenario.initiator;
        Object methodResult = new Object();

        // Stateful backing for requests persisted by the aspect.
        Map<UUID, ApprovalRequestEntity> requests = new HashMap<>();
        ApprovalRequestMapper requestMapper = mock(ApprovalRequestMapper.class);
        when(requestMapper.insert(any(ApprovalRequestEntity.class))).thenAnswer(inv -> {
            ApprovalRequestEntity e = inv.getArgument(0);
            if (e.getId() == null) {
                e.setId(UUID.randomUUID());
            }
            requests.put(e.getId(), e);
            return 1;
        });

        ApprovalPolicyMapper policyMapper = mock(ApprovalPolicyMapper.class);
        ApprovalPolicyEntity policy = ApprovalPolicyEntity.builder()
                .id(UUID.randomUUID())
                .orgId(orgId)
                .name("gate")
                .module(MODULE)
                .actionType(ACTION)
                .approvalType("multi_level")
                .approverUserIds(scenario.approverIdStrings())
                .enabled(true)
                .build();
        // findGoverningPolicy already filters to enabled, non-"none" policies; the
        // boolean models "an enabled policy with met threshold applies" vs not.
        when(policyMapper.selectOne(any())).thenReturn(policyApplies ? policy : null);

        ApprovalAspect aspect = new ApprovalAspect(policyMapper, requestMapper, new ObjectMapper());

        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        when(joinPoint.getArgs()).thenReturn(new Object[]{"arg", 42});
        when(joinPoint.proceed()).thenReturn(methodResult);
        RequiresApproval annotation = requiresApproval(MODULE, ACTION);

        setAuthenticatedUser(userId, orgId);
        try {
            Object result = aspect.gate(joinPoint, annotation);

            if (policyApplies) {
                // Gated: the method is short-circuited and a pending request is parked.
                assertThat(result).as("gated action returns null instead of executing").isNull();
                verify(joinPoint, never()).proceed();
                assertThat(requests).hasSize(1);
                ApprovalRequestEntity parked = requests.values().iterator().next();
                assertThat(parked.getStatus()).isEqualTo("pending");
                assertThat(parked.getCurrentLevel()).isEqualTo(1);
                assertThat(parked.getInitiatedBy()).isEqualTo(userId);
                assertThat(parked.getModule()).isEqualTo(MODULE);
                assertThat(parked.getActionType()).isEqualTo(ACTION);
                assertThat(parked.getPolicyId()).isEqualTo(policy.getId());
            } else {
                // No governing policy: the action executes immediately, nothing parked.
                assertThat(result).isSameAs(methodResult);
                verify(joinPoint).proceed();
                assertThat(requests).isEmpty();
            }
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    // ------------------------------------------------------------------
    // Fixture: service wired to stateful in-memory mappers + recording executor.
    // ------------------------------------------------------------------

    private static final class Fixture {
        final Map<UUID, ApprovalRequestEntity> requests = new HashMap<>();
        final List<ApprovalDecisionEntity> decisions = new ArrayList<>();
        final Map<UUID, AtomicInteger> executions = new HashMap<>();
        final ApprovalWorkflowServiceImpl service;
        final Scenario scenario;

        Fixture(Scenario scenario) {
            this.scenario = scenario;

            ApprovalRequestMapper requestMapper = mock(ApprovalRequestMapper.class);
            when(requestMapper.selectById(any())).thenAnswer(inv -> requests.get(inv.getArgument(0)));
            when(requestMapper.updateById(any(ApprovalRequestEntity.class))).thenAnswer(inv -> {
                ApprovalRequestEntity e = inv.getArgument(0);
                // selectById returns the live object, so the service mutates it in place;
                // store it back to mimic a real persistence round-trip.
                requests.put(e.getId(), e);
                return 1;
            });
            when(requestMapper.insert(any(ApprovalRequestEntity.class))).thenAnswer(inv -> {
                ApprovalRequestEntity e = inv.getArgument(0);
                if (e.getId() == null) {
                    e.setId(UUID.randomUUID());
                }
                requests.put(e.getId(), e);
                return 1;
            });

            ApprovalDecisionMapper decisionMapper = mock(ApprovalDecisionMapper.class);
            when(decisionMapper.insert(any(ApprovalDecisionEntity.class))).thenAnswer(inv -> {
                decisions.add(inv.getArgument(0));
                return 1;
            });

            ApprovalPolicyMapper policyMapper = mock(ApprovalPolicyMapper.class);
            ApprovalPolicyEntity policy = ApprovalPolicyEntity.builder()
                    .id(scenario.policyId)
                    .orgId(UUID.randomUUID())
                    .name("policy")
                    .module(MODULE)
                    .actionType(ACTION)
                    .approvalType("multi_level")
                    .approverUserIds(scenario.approverIdStrings())
                    .enabled(true)
                    .build();
            when(policyMapper.selectById(scenario.policyId)).thenReturn(policy);

            AuditLogService auditLogService = mock(AuditLogService.class);

            RecordingExecutor executor = new RecordingExecutor(executions);

            this.service = new ApprovalWorkflowServiceImpl(requestMapper, decisionMapper,
                    policyMapper, auditLogService, List.of(executor));
        }

        UUID createPendingRequest() {
            UUID id = UUID.randomUUID();
            ApprovalRequestEntity request = ApprovalRequestEntity.builder()
                    .id(id)
                    .policyId(scenario.policyId)
                    .module(MODULE)
                    .actionType(ACTION)
                    .initiatedBy(scenario.initiator)
                    .status("pending")
                    .currentLevel(1)
                    .build();
            requests.put(id, request);
            return id;
        }

        ApprovalRequestEntity request(UUID id) {
            return requests.get(id);
        }

        String status(UUID id) {
            return requests.get(id).getStatus();
        }

        int currentLevel(UUID id) {
            return requests.get(id).getCurrentLevel();
        }

        int executionCount(UUID id) {
            AtomicInteger c = executions.get(id);
            return c == null ? 0 : c.get();
        }

        List<ApprovalDecisionEntity> decisionsFor(UUID id) {
            return decisions.stream().filter(d -> id.equals(d.getRequestId())).collect(Collectors.toList());
        }
    }

    /** Counts how many times the gated action is executed, per request id. */
    private static final class RecordingExecutor implements ApprovalActionExecutor {
        private final Map<UUID, AtomicInteger> executions;

        RecordingExecutor(Map<UUID, AtomicInteger> executions) {
            this.executions = executions;
        }

        @Override
        public String module() {
            return MODULE;
        }

        @Override
        public String actionType() {
            return ACTION;
        }

        @Override
        public void execute(ApprovalRequestEntity request) {
            executions.computeIfAbsent(request.getId(), k -> new AtomicInteger()).incrementAndGet();
        }
    }

    // ------------------------------------------------------------------
    // Generators
    // ------------------------------------------------------------------

    /**
     * A scenario carries a distinct initiator and an ordered list of distinct
     * level approvers (one approver per multi_level level, Req 12.1.5).
     */
    record Scenario(UUID policyId, UUID initiator, List<UUID> levelApprovers) {
        List<String> approverIdStrings() {
            return levelApprovers.stream().map(UUID::toString).collect(Collectors.toList());
        }

        static Scenario build(int levels, long seed) {
            // Deterministic, mutually-distinct ids: initiator uses a least-significant
            // bit that no level approver (0..levels-1) can collide with.
            UUID initiator = new UUID(seed, -1L);
            List<UUID> approvers = new ArrayList<>(levels);
            for (int i = 0; i < levels; i++) {
                approvers.add(new UUID(seed, i));
            }
            return new Scenario(UUID.randomUUID(), initiator, approvers);
        }
    }

    @Provide
    Arbitrary<Scenario> scenarios() {
        return buildScenarios(1, 5);
    }

    /** Scenarios with at least two levels, so a "different level" approver exists. */
    @Provide
    Arbitrary<Scenario> multiLevelScenarios() {
        return buildScenarios(2, 5);
    }

    private Arbitrary<Scenario> buildScenarios(int minLevels, int maxLevels) {
        Arbitrary<Integer> levels = Arbitraries.integers().between(minLevels, maxLevels);
        Arbitrary<Long> seeds = Arbitraries.longs();
        return Combinators.combine(levels, seeds).as(Scenario::build);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static void setAuthenticatedUser(UUID userId, UUID orgId) {
        CurrentUser principal = CurrentUser.builder()
                .userId(userId.toString())
                .orgId(orgId.toString())
                .build();
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(principal, null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private static RequiresApproval requiresApproval(String module, String action) {
        return new RequiresApproval() {
            @Override
            public Class<? extends Annotation> annotationType() {
                return RequiresApproval.class;
            }

            @Override
            public String module() {
                return module;
            }

            @Override
            public String action() {
                return action;
            }
        };
    }
}
