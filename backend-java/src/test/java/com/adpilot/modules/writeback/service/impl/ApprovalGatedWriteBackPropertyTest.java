package com.adpilot.modules.writeback.service.impl;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.common.security.CurrentUser;
import com.adpilot.modules.apisync.connector.PlatformWriteConnector;
import com.adpilot.modules.apisync.model.ConnectionContext;
import com.adpilot.modules.apisync.model.PlatformChange;
import com.adpilot.modules.apisync.model.PlatformWriteResult;
import com.adpilot.modules.approval.annotation.RequiresApproval;
import com.adpilot.modules.approval.aspect.ApprovalAspect;
import com.adpilot.modules.approval.entity.ApprovalDecisionEntity;
import com.adpilot.modules.approval.entity.ApprovalPolicyEntity;
import com.adpilot.modules.approval.entity.ApprovalRequestEntity;
import com.adpilot.modules.approval.mapper.ApprovalDecisionMapper;
import com.adpilot.modules.approval.mapper.ApprovalPolicyMapper;
import com.adpilot.modules.approval.mapper.ApprovalRequestMapper;
import com.adpilot.modules.approval.service.ApprovalActionExecutor;
import com.adpilot.modules.approval.service.impl.ApprovalWorkflowServiceImpl;
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
 * Property-based test for approval-gated write-back and automation submission.
 *
 * Feature: core-platform-completion, Property 26: Approval-governed write-back
 * and automation submit only after approval.
 *
 * <p>When a write-back (an applied AI recommendation, Req 13.1.4) or an
 * automation change (a bid adjustment / negative keyword, Req 13.2.6) is
 * governed by an enabled approval policy whose threshold is met, the change is
 * submitted to the live platform <em>only after</em> approval is granted; until
 * then it is never submitted. The connector submission happens <em>iff</em>
 * approval has been granted — never before, and never when the request is
 * rejected.</p>
 *
 * <p>The flow is exercised end to end with the real
 * {@link ApprovalAspect} (which gates the governed call) and the real
 * {@link ApprovalWorkflowServiceImpl} (which routes, sequences, and—on full
 * approval—resumes the parked action through its
 * {@link ApprovalActionExecutor}). The resumed executor is the only path that
 * reaches the platform, so a recording stub {@link PlatformWriteConnector}
 * directly observes <em>whether and when</em> the change is submitted. The
 * MyBatis mappers are mocked with stateful in-memory backing so the multi-level
 * workflow runs without a database or a live platform.</p>
 *
 * Validates: Requirements 13.1.4, 13.2.6
 */
class ApprovalGatedWriteBackPropertyTest {

    /**
     * The two governed change sources covered by Property 26: recommendation
     * write-back (Req 13.1.4) and automation changes (Req 13.2.6). The
     * module/action pair is what the {@code ApprovalAspect} matches a policy on
     * and what the workflow service routes the resume executor by.
     */
    enum GovernedSource {
        WRITE_BACK("recommendation", "apply"),
        AUTOMATION("automation", "bid_change");

        final String module;
        final String action;

        GovernedSource(String module, String action) {
            this.module = module;
            this.action = action;
        }
    }

    // ------------------------------------------------------------------
    // Property 26: full approval submits the change exactly once, only after
    // every required level has approved — never before.
    // ------------------------------------------------------------------

    // Feature: core-platform-completion, Property 26: Approval-governed write-back and automation submit only after approval
    @Property(tries = 200)
    void governedChangeIsSubmittedToThePlatformOnlyAfterFullApproval(
            @ForAll("scenarios") Scenario scenario) {

        Fixture fixture = new Fixture(scenario);

        // The governed write-back/automation call is intercepted by the aspect.
        Object gateResult = fixture.invokeGovernedAction();

        // Gated: the action is short-circuited and parked pending — and crucially
        // NOTHING has been submitted to the platform yet (Req 13.1.4 / 13.2.6).
        assertThat(gateResult).as("gated governed action returns null instead of executing").isNull();
        assertThat(fixture.submissionCount()).as("no submission before approval").isZero();
        UUID requestId = fixture.parkedRequestId();
        assertThat(fixture.status(requestId)).isEqualTo("pending");

        int levels = scenario.levelApprovers.size();
        for (int i = 0; i < levels; i++) {
            // Invariant before this level's approval: still pending, still not submitted.
            assertThat(fixture.status(requestId)).isEqualTo("pending");
            assertThat(fixture.submissionCount())
                    .as("not submitted while approval is incomplete (level %d/%d)", i + 1, levels)
                    .isZero();

            fixture.workflow.approve(requestId, scenario.levelApprovers.get(i), "ok");

            if (i < levels - 1) {
                // Intermediate level approved: still no submission, more levels remain.
                assertThat(fixture.status(requestId)).isEqualTo("pending");
                assertThat(fixture.submissionCount())
                        .as("not submitted until the final level approves").isZero();
            }
        }

        // All required levels approved: the change is now submitted to the
        // platform exactly once, and only at this point (Req 13.1.4 / 13.2.6).
        assertThat(fixture.status(requestId)).isEqualTo("approved");
        assertThat(fixture.submissionCount()).isEqualTo(1);

        // The submitted change carries the governed source's provenance.
        PlatformChange submitted = fixture.lastSubmittedChange();
        assertThat(submitted).isNotNull();
        assertThat(submitted.sourceType()).isEqualTo(scenario.source.module);

        // Exactly once: a further (now-terminal) decision neither succeeds nor
        // triggers a second submission.
        assertThatThrownBy(() -> fixture.workflow.approve(requestId, scenario.levelApprovers.get(0), "again"))
                .isInstanceOf(BusinessException.class);
        assertThat(fixture.submissionCount()).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // Property 26: without approval (a rejection at any level) the governed
    // change is never submitted to the platform.
    // ------------------------------------------------------------------

    // Feature: core-platform-completion, Property 26: Approval-governed write-back and automation submit only after approval
    @Property(tries = 200)
    void governedChangeRejectedBeforeFullApprovalIsNeverSubmitted(
            @ForAll("scenarios") Scenario scenario,
            @ForAll int rawRejectLevel) {

        Fixture fixture = new Fixture(scenario);

        Object gateResult = fixture.invokeGovernedAction();
        assertThat(gateResult).isNull();
        assertThat(fixture.submissionCount()).isZero();
        UUID requestId = fixture.parkedRequestId();

        int levels = scenario.levelApprovers.size();
        int k = Math.floorMod(rawRejectLevel, levels) + 1; // 1-based level to reject at

        // Approve every level up to k-1 (no submission yet), then reject at level k.
        for (int i = 0; i < k - 1; i++) {
            fixture.workflow.approve(requestId, scenario.levelApprovers.get(i), null);
            assertThat(fixture.submissionCount()).isZero();
        }
        fixture.workflow.reject(requestId, scenario.levelApprovers.get(k - 1), "denied");

        // Rejection is terminal: approval was never granted, so the change is
        // never submitted to the platform (Req 13.1.4 / 13.2.6).
        assertThat(fixture.status(requestId)).isEqualTo("rejected");
        assertThat(fixture.submissionCount()).isZero();

        // The terminal request cannot be approved afterwards, and still nothing is submitted.
        assertThatThrownBy(() -> fixture.workflow.approve(requestId, scenario.levelApprovers.get(k - 1), "x"))
                .isInstanceOf(BusinessException.class);
        assertThat(fixture.submissionCount()).isZero();
    }

    // ------------------------------------------------------------------
    // Property 26 (the "iff", other direction): when an enabled policy does NOT
    // govern the action, it is not gated and executes immediately — i.e. the
    // approval gate is exactly what defers the submission.
    // ------------------------------------------------------------------

    // Feature: core-platform-completion, Property 26: Approval-governed write-back and automation submit only after approval
    @Property(tries = 200)
    void ungovernedChangeIsSubmittedImmediatelyWithoutApproval(
            @ForAll("scenarios") Scenario scenario) throws Throwable {

        Fixture fixture = new Fixture(scenario);

        // No enabled governing policy applies -> the aspect must let the call
        // proceed, and the underlying action submits to the platform right away.
        Object result = fixture.invokeUngovernedAction();

        assertThat(result).as("ungoverned action executes instead of being gated").isNotNull();
        assertThat(fixture.submissionCount())
                .as("an ungoverned change is submitted immediately, no approval involved")
                .isEqualTo(1);
        // Nothing was parked for approval.
        assertThat(fixture.parkedRequestOrNull()).isNull();
    }

    // ------------------------------------------------------------------
    // Fixture: real ApprovalAspect + real ApprovalWorkflowServiceImpl wired over
    // stateful in-memory mappers, with a submission-recording connector reached
    // only through the resume executor.
    // ------------------------------------------------------------------

    private final class Fixture {
        final Scenario scenario;
        final UUID orgId = UUID.randomUUID();
        final UUID storeId = UUID.randomUUID();

        final Map<UUID, ApprovalRequestEntity> requests = new HashMap<>();
        final List<ApprovalDecisionEntity> decisions = new ArrayList<>();

        final RecordingWriteConnector connector = new RecordingWriteConnector();
        final ApprovalAspect aspect;
        final ApprovalWorkflowServiceImpl workflow;
        final ApprovalPolicyEntity policy;

        Fixture(Scenario scenario) {
            this.scenario = scenario;

            // ----- stateful request mapper shared by aspect + workflow ------------
            ApprovalRequestMapper requestMapper = mock(ApprovalRequestMapper.class);
            when(requestMapper.insert(any(ApprovalRequestEntity.class))).thenAnswer(inv -> {
                ApprovalRequestEntity e = inv.getArgument(0);
                if (e.getId() == null) {
                    e.setId(UUID.randomUUID());
                }
                requests.put(e.getId(), e);
                return 1;
            });
            when(requestMapper.selectById(any())).thenAnswer(inv -> requests.get(inv.getArgument(0)));
            when(requestMapper.updateById(any(ApprovalRequestEntity.class))).thenAnswer(inv -> {
                ApprovalRequestEntity e = inv.getArgument(0);
                requests.put(e.getId(), e);
                return 1;
            });

            ApprovalDecisionMapper decisionMapper = mock(ApprovalDecisionMapper.class);
            when(decisionMapper.insert(any(ApprovalDecisionEntity.class))).thenAnswer(inv -> {
                decisions.add(inv.getArgument(0));
                return 1;
            });

            // ----- policy that governs this module/action for the org -------------
            this.policy = ApprovalPolicyEntity.builder()
                    .id(scenario.policyId)
                    .orgId(orgId)
                    .name("gate")
                    .module(scenario.source.module)
                    .actionType(scenario.source.action)
                    .approvalType("multi_level")
                    .approverUserIds(scenario.approverIdStrings())
                    .enabled(true)
                    .build();
            ApprovalPolicyMapper policyMapper = mock(ApprovalPolicyMapper.class);
            // The aspect's findGoverningPolicy() filters to an enabled, non-"none"
            // policy; returning it models "an enabled policy with met threshold".
            when(policyMapper.selectOne(any())).thenReturn(policy);
            when(policyMapper.selectById(scenario.policyId)).thenReturn(policy);

            AuditLogService auditLogService = mock(AuditLogService.class);

            this.aspect = new ApprovalAspect(policyMapper, requestMapper, new ObjectMapper());

            // The resume executor is the ONLY path that reaches the platform; it
            // submits the parked change through the connector when (and only when)
            // the workflow service resumes a fully-approved action (Req 12.1.3).
            SubmittingExecutor executor = new SubmittingExecutor(
                    scenario.source.module, scenario.source.action, connector, storeId);

            this.workflow = new ApprovalWorkflowServiceImpl(
                    requestMapper, decisionMapper, policyMapper, auditLogService, List.of(executor));
        }

        /** Invoke the governed action through the aspect (gets gated when a policy applies). */
        Object invokeGovernedAction() {
            setAuthenticatedUser(scenario.initiator, orgId);
            try {
                ProceedingJoinPoint jp = mock(ProceedingJoinPoint.class);
                when(jp.getArgs()).thenReturn(new Object[]{scenario.source.module, storeId.toString()});
                // If the aspect ever (wrongly) proceeds for a governed action, the
                // underlying action would submit — making the "no submission before
                // approval" assertion fail, which is exactly what we want to catch.
                when(jp.proceed()).thenAnswer(inv -> {
                    connector.submit(connectionContext(), changeFor(scenario.source));
                    return new Object();
                });
                return aspect.gate(jp, requiresApproval(scenario.source.module, scenario.source.action));
            } catch (Throwable t) {
                throw new RuntimeException(t);
            }
        }

        /** Invoke the action when no enabled policy governs it (aspect proceeds). */
        Object invokeUngovernedAction() throws Throwable {
            // Re-wire the aspect with a policy mapper that finds no governing policy.
            ApprovalPolicyMapper noPolicy = mock(ApprovalPolicyMapper.class);
            when(noPolicy.selectOne(any())).thenReturn(null);
            ApprovalRequestMapper requestMapper = mock(ApprovalRequestMapper.class);
            when(requestMapper.insert(any(ApprovalRequestEntity.class))).thenAnswer(inv -> {
                ApprovalRequestEntity e = inv.getArgument(0);
                if (e.getId() == null) {
                    e.setId(UUID.randomUUID());
                }
                requests.put(e.getId(), e);
                return 1;
            });
            ApprovalAspect ungovernedAspect = new ApprovalAspect(noPolicy, requestMapper, new ObjectMapper());

            setAuthenticatedUser(scenario.initiator, orgId);
            try {
                ProceedingJoinPoint jp = mock(ProceedingJoinPoint.class);
                when(jp.getArgs()).thenReturn(new Object[]{scenario.source.module});
                when(jp.proceed()).thenAnswer(inv -> {
                    connector.submit(connectionContext(), changeFor(scenario.source));
                    return new Object();
                });
                return ungovernedAspect.gate(jp, requiresApproval(scenario.source.module, scenario.source.action));
            } finally {
                SecurityContextHolder.clearContext();
            }
        }

        ConnectionContext connectionContext() {
            return new ConnectionContext(UUID.randomUUID(), storeId, scenario.source.module, Map.of());
        }

        int submissionCount() {
            return connector.count.get();
        }

        PlatformChange lastSubmittedChange() {
            return connector.lastChange;
        }

        UUID parkedRequestId() {
            assertThat(requests).as("a pending approval_request was parked").hasSize(1);
            return requests.keySet().iterator().next();
        }

        UUID parkedRequestOrNull() {
            return requests.isEmpty() ? null : requests.keySet().iterator().next();
        }

        String status(UUID id) {
            return requests.get(id).getStatus();
        }
    }

    /** Records every submission so the test can observe whether/when it happened. */
    private static final class RecordingWriteConnector implements PlatformWriteConnector {
        final AtomicInteger count = new AtomicInteger();
        volatile PlatformChange lastChange;

        @Override
        public String platform() {
            return "amazon_ads";
        }

        @Override
        public PlatformWriteResult submit(ConnectionContext ctx, PlatformChange change) {
            lastChange = change;
            count.incrementAndGet();
            return PlatformWriteResult.accepted("ref-" + count.get(), "ok");
        }
    }

    /**
     * Resumes a fully-approved governed action by submitting its change to the
     * platform. Mirrors a per-module executor registered for write-back /
     * automation, and is invoked exactly once after all levels approve.
     */
    private static final class SubmittingExecutor implements ApprovalActionExecutor {
        private final String module;
        private final String action;
        private final PlatformWriteConnector connector;
        private final UUID storeId;

        SubmittingExecutor(String module, String action, PlatformWriteConnector connector, UUID storeId) {
            this.module = module;
            this.action = action;
            this.connector = connector;
            this.storeId = storeId;
        }

        @Override
        public String module() {
            return module;
        }

        @Override
        public String actionType() {
            return action;
        }

        @Override
        public void execute(ApprovalRequestEntity request) {
            connector.submit(
                    new ConnectionContext(UUID.randomUUID(), storeId, "amazon_ads", Map.of()),
                    changeFor(module, action, storeId, request.getId()));
        }
    }

    private static PlatformChange changeFor(GovernedSource source) {
        return changeFor(source.module, source.action, UUID.randomUUID(), UUID.randomUUID());
    }

    private static PlatformChange changeFor(String sourceType, String changeType, UUID storeId, UUID sourceId) {
        return new PlatformChange(
                "amazon_ads", storeId, changeType, "keyword", UUID.randomUUID().toString(),
                "0.50", "0.75", sourceType, sourceId.toString());
    }

    // ------------------------------------------------------------------
    // Generators
    // ------------------------------------------------------------------

    /**
     * A scenario carries the governed source (write-back or automation), a
     * distinct initiator, and an ordered list of distinct level approvers (one
     * per multi_level level, none equal to the initiator so self-approval can
     * never short-circuit the gate).
     */
    record Scenario(GovernedSource source, UUID policyId, UUID initiator, List<UUID> levelApprovers) {
        List<String> approverIdStrings() {
            return levelApprovers.stream().map(UUID::toString).collect(Collectors.toList());
        }

        static Scenario build(GovernedSource source, int levels, long seed) {
            UUID initiator = new UUID(seed, -1L);
            List<UUID> approvers = new ArrayList<>(levels);
            for (int i = 0; i < levels; i++) {
                approvers.add(new UUID(seed, i));
            }
            return new Scenario(source, UUID.randomUUID(), initiator, approvers);
        }
    }

    @Provide
    Arbitrary<Scenario> scenarios() {
        Arbitrary<GovernedSource> sources = Arbitraries.of(GovernedSource.class);
        Arbitrary<Integer> levels = Arbitraries.integers().between(1, 5);
        Arbitrary<Long> seeds = Arbitraries.longs();
        return Combinators.combine(sources, levels, seeds).as(Scenario::build);
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
