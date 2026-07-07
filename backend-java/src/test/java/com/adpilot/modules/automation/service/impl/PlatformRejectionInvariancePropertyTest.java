package com.adpilot.modules.automation.service.impl;

import com.adpilot.common.utils.CryptoUtil;
import com.adpilot.modules.advertising.entity.KeywordEntity;
import com.adpilot.modules.advertising.mapper.KeywordMapper;
import com.adpilot.modules.advertising.mapper.NegativeKeywordMapper;
import com.adpilot.modules.advertising.mapper.SearchTermMapper;
import com.adpilot.modules.apisync.connector.PlatformWriteConnector;
import com.adpilot.modules.apisync.entity.PlatformConnectionEntity;
import com.adpilot.modules.apisync.mapper.PlatformConnectionMapper;
import com.adpilot.modules.apisync.model.ConnectionContext;
import com.adpilot.modules.apisync.model.PlatformChange;
import com.adpilot.modules.apisync.model.PlatformWriteResult;
import com.adpilot.modules.audit.service.AuditLogService;
import com.adpilot.modules.automation.entity.AutomationRuleEntity;
import com.adpilot.modules.automation.mapper.AutomationRuleMapper;
import com.adpilot.modules.automation.service.AutomationRunner;
import com.adpilot.modules.automation.vo.AutomationRunSummaryVo;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.adpilot.modules.automation.service.impl.AutomationRunnerImpl.ACTION_APPLIED;
import static com.adpilot.modules.automation.service.impl.AutomationRunnerImpl.ACTION_REJECTED;
import static com.adpilot.modules.automation.service.impl.AutomationRunnerImpl.AUDIT_ENTITY_TYPE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Property-based test for {@link AutomationRunnerImpl#executeRule(AutomationRuleEntity)}.
 *
 * Feature: core-platform-completion, Property 27: Platform rejection leaves
 * internal state unchanged and records the reason.
 *
 * <p>This is the automation half of Property 27 (the write-back half lives in
 * {@code com.adpilot.modules.writeback.service.impl.PlatformRejectionInvariancePropertyTest}).
 * For any automated bid change that the live platform rejects — whether the
 * connector returns a {@link PlatformWriteResult#rejected(String) rejected}
 * result, throws a transport/credential error, returns {@code null}, or no
 * write connector is registered for the platform — the affected internal
 * keyword is left unchanged (its bid is never updated via
 * {@link KeywordMapper#updateById}) and exactly one rejection reason is recorded
 * in the audit trail. Conversely, when the platform accepts, the internal bid is
 * updated.</p>
 *
 * Validates: Requirements 13.1.3, 13.2.7
 */
class PlatformRejectionInvariancePropertyTest {

    private static final List<String> PLATFORMS =
            List.of("amazon_ads", "shopify", "woocommerce", "amazon_sp_api", "tiktok");

    /** The ways the live platform can fail to accept a submitted change. */
    enum RejectionKind { REJECTED, THROW, NULL_RESULT, NO_CONNECTOR }

    record RejectionScenario(String platform, double currentBid, int adjustmentPct,
                             RejectionKind kind, String reason) {
    }

    record AcceptedScenario(String platform, double currentBid, int adjustmentPct,
                            String platformReference, String message) {
    }

    // Feature: core-platform-completion, Property 27: Platform rejection leaves internal state unchanged and records the reason
    // Req 13.2.7: on platform rejection an automated change is not mutated and the reason is audited.
    @Property(tries = 200)
    void rejectionLeavesKeywordUnchangedAndRecordsReason(
            @ForAll("rejectionScenarios") RejectionScenario s) {

        AutomationRuleMapper automationRuleMapper = mock(AutomationRuleMapper.class);
        KeywordMapper keywordMapper = mock(KeywordMapper.class);
        SearchTermMapper searchTermMapper = mock(SearchTermMapper.class);
        NegativeKeywordMapper negativeKeywordMapper = mock(NegativeKeywordMapper.class);
        PlatformConnectionMapper connectionMapper = mock(PlatformConnectionMapper.class);
        AuditLogService auditLogService = mock(AuditLogService.class);
        CryptoUtil cryptoUtil = mock(CryptoUtil.class);

        BigDecimal currentBid = bid(s.currentBid());
        UUID storeId = UUID.randomUUID();
        KeywordEntity keyword = buildKeyword(storeId, currentBid);

        AutomationRuleEntity rule = buildBidRule(storeId, s.adjustmentPct());
        UUID ruleId = rule.getId();

        when(keywordMapper.selectList(any())).thenReturn(List.of(keyword));
        when(connectionMapper.selectList(any()))
                .thenReturn(List.of(buildConnection(storeId, s.platform())));

        // Build the connector wiring for this rejection kind.
        String expectedReason;
        List<PlatformWriteConnector> connectors;
        switch (s.kind()) {
            case REJECTED -> {
                connectors = List.of(new StubWriteConnector(
                        s.platform(), PlatformWriteResult.rejected(s.reason()), null));
                expectedReason = s.reason();
            }
            case THROW -> {
                connectors = List.of(new StubWriteConnector(
                        s.platform(), null, new RuntimeException(s.reason())));
                expectedReason = s.reason();
            }
            case NULL_RESULT -> {
                connectors = List.of(new StubWriteConnector(s.platform(), null, null));
                expectedReason = "Connector returned no result";
            }
            case NO_CONNECTOR -> {
                connectors = List.of(); // no write connector registered for the platform
                expectedReason = "Write-back is not available for platform '"
                        + s.platform() + "': no write connector is registered";
            }
            default -> throw new IllegalStateException();
        }

        AutomationRunnerImpl runner = new AutomationRunnerImpl(
                automationRuleMapper, keywordMapper, searchTermMapper, negativeKeywordMapper,
                connectionMapper, auditLogService, cryptoUtil, new ObjectMapper(),
                /* self (proxy) unused for a direct executeRule call */ null, connectors);

        AutomationRunSummaryVo summary = runner.executeRule(rule);

        // The change was submitted to the platform and rejected.
        assertThat(summary.getChangesSubmitted()).isEqualTo(1);
        assertThat(summary.getChangesRejected()).isEqualTo(1);
        assertThat(summary.getChangesAccepted()).isZero();

        // Req 13.2.7: the internal keyword is left unchanged — its bid is never persisted.
        verify(keywordMapper, never()).updateById(any());
        assertThat(keyword.getBid()).isEqualByComparingTo(currentBid);

        // Req 13.2.7: exactly one rejection reason is recorded in the audit trail,
        // and no "applied" automation audit entry is written.
        ArgumentCaptor<Object> detailCaptor = ArgumentCaptor.forClass(Object.class);
        verify(auditLogService, times(1)).createLog(
                any(), any(), eq(ACTION_REJECTED), eq(AUDIT_ENTITY_TYPE), eq(ruleId), detailCaptor.capture());
        verify(auditLogService, never()).createLog(
                any(), any(), eq(ACTION_APPLIED), any(), any(), any());

        // The recorded reason is the platform's failure reason.
        assertThat(recordedReason(detailCaptor.getValue())).isEqualTo(expectedReason);
    }

    // Feature: core-platform-completion, Property 27: Platform rejection leaves internal state unchanged and records the reason
    // Companion: when the platform accepts, the automated bid change IS applied (and audited as applied).
    @Property(tries = 200)
    void acceptanceAppliesTheBidChange(@ForAll("acceptedScenarios") AcceptedScenario s) {

        AutomationRuleMapper automationRuleMapper = mock(AutomationRuleMapper.class);
        KeywordMapper keywordMapper = mock(KeywordMapper.class);
        SearchTermMapper searchTermMapper = mock(SearchTermMapper.class);
        NegativeKeywordMapper negativeKeywordMapper = mock(NegativeKeywordMapper.class);
        PlatformConnectionMapper connectionMapper = mock(PlatformConnectionMapper.class);
        AuditLogService auditLogService = mock(AuditLogService.class);
        CryptoUtil cryptoUtil = mock(CryptoUtil.class);

        BigDecimal currentBid = bid(s.currentBid());
        UUID storeId = UUID.randomUUID();
        KeywordEntity keyword = buildKeyword(storeId, currentBid);

        AutomationRuleEntity rule = buildBidRule(storeId, s.adjustmentPct());

        when(keywordMapper.selectList(any())).thenReturn(List.of(keyword));
        when(connectionMapper.selectList(any()))
                .thenReturn(List.of(buildConnection(storeId, s.platform())));

        StubWriteConnector connector = new StubWriteConnector(
                s.platform(), PlatformWriteResult.accepted(s.platformReference(), s.message()), null);

        AutomationRunnerImpl runner = new AutomationRunnerImpl(
                automationRuleMapper, keywordMapper, searchTermMapper, negativeKeywordMapper,
                connectionMapper, auditLogService, cryptoUtil, new ObjectMapper(),
                null, List.of(connector));

        // Pre-compute the clamped bid the runner should submit and persist.
        BigDecimal proposed = currentBid
                .multiply(BigDecimal.ONE.add(BigDecimal.valueOf(s.adjustmentPct()).movePointLeft(2)))
                .setScale(4, RoundingMode.HALF_UP);
        BigDecimal expectedBid = AutomationRunner.clampBid(proposed, rule.getMinBid(), rule.getMaxBid());

        AutomationRunSummaryVo summary = runner.executeRule(rule);

        // The change was submitted and accepted.
        assertThat(summary.getChangesSubmitted()).isEqualTo(1);
        assertThat(summary.getChangesAccepted()).isEqualTo(1);
        assertThat(summary.getChangesRejected()).isZero();

        // Req 13.2.2: the internal bid is updated only after the platform accepts.
        verify(keywordMapper, times(1)).updateById(eq(keyword));
        assertThat(keyword.getBid()).isEqualByComparingTo(expectedBid);

        // The accepted change is audited as applied, never as rejected.
        verify(auditLogService, times(1)).createLog(
                any(), any(), eq(ACTION_APPLIED), eq(AUDIT_ENTITY_TYPE), eq(rule.getId()), any());
        verify(auditLogService, never()).createLog(
                any(), any(), eq(ACTION_REJECTED), any(), any(), any());
    }

    // --- stub connector -------------------------------------------------------

    /** A configurable {@link PlatformWriteConnector} that accepts, rejects, throws, or returns null. */
    static final class StubWriteConnector implements PlatformWriteConnector {
        private final String platform;
        private final PlatformWriteResult result; // may be null to simulate a null return
        private final RuntimeException toThrow;    // non-null => transport/credential failure

        StubWriteConnector(String platform, PlatformWriteResult result, RuntimeException toThrow) {
            this.platform = platform;
            this.result = result;
            this.toThrow = toThrow;
        }

        @Override
        public String platform() {
            return platform;
        }

        @Override
        public PlatformWriteResult submit(ConnectionContext ctx, PlatformChange change) {
            if (toThrow != null) {
                throw toThrow;
            }
            return result;
        }
    }

    // --- fixtures -------------------------------------------------------------

    /** A positive bid with the same 4-decimal scale the runner uses. */
    private static BigDecimal bid(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP);
    }

    private static KeywordEntity buildKeyword(UUID storeId, BigDecimal bid) {
        return KeywordEntity.builder()
                .id(UUID.randomUUID())
                .campaignId(UUID.randomUUID())
                .adGroupId(UUID.randomUUID())
                .storeId(storeId)
                .keywordText("kw")
                .status("enabled")
                .bid(bid)
                .build();
    }

    /**
     * A bid-adjustment rule with no threshold (so every enabled keyword matches)
     * and very wide bounds (so the adjusted bid is submitted unclamped, i.e. it
     * differs from the current bid whenever {@code adjustmentPct != 0}).
     */
    private static AutomationRuleEntity buildBidRule(UUID storeId, int adjustmentPct) {
        return AutomationRuleEntity.builder()
                .id(UUID.randomUUID())
                .storeId(storeId)
                .ruleType(AutomationRuleEntity.TYPE_BID_ADJUSTMENT)
                .enabled(true)
                .conditionJson("{\"adjustmentPct\": " + adjustmentPct + "}")
                .minBid(new BigDecimal("0.0001"))
                .maxBid(new BigDecimal("100000"))
                .build();
    }

    private static PlatformConnectionEntity buildConnection(UUID storeId, String platform) {
        return PlatformConnectionEntity.builder()
                .id(UUID.randomUUID())
                .storeId(storeId)
                .platform(platform)
                .status("connected")
                .configEncrypted(null) // blank config -> decrypt path is skipped, CryptoUtil untouched
                .build();
    }

    @SuppressWarnings("unchecked")
    private static String recordedReason(Object detail) {
        assertThat(detail).isInstanceOf(Map.class);
        Object response = ((Map<String, Object>) detail).get("platformResponse");
        assertThat(response).isInstanceOf(Map.class);
        return String.valueOf(((Map<String, Object>) response).get("message"));
    }

    // --- generators -----------------------------------------------------------

    @Provide
    Arbitrary<RejectionScenario> rejectionScenarios() {
        return Combinators.combine(
                        Arbitraries.of(PLATFORMS),
                        currentBids(),
                        adjustmentPcts(),
                        Arbitraries.of(RejectionKind.class),
                        nonBlank())
                .as(RejectionScenario::new);
    }

    @Provide
    Arbitrary<AcceptedScenario> acceptedScenarios() {
        return Combinators.combine(
                        Arbitraries.of(PLATFORMS),
                        currentBids(),
                        adjustmentPcts(),
                        nonBlank(),
                        nonBlank())
                .as(AcceptedScenario::new);
    }

    /** Positive current bids well within the rule's bounds. */
    private Arbitrary<Double> currentBids() {
        return Arbitraries.doubles().between(0.10, 50.00);
    }

    /** Non-zero positive percentages, so the adjusted bid always differs from the current bid. */
    private Arbitrary<Integer> adjustmentPcts() {
        return Arbitraries.integers().between(1, 90);
    }

    /** Non-blank alphanumeric strings, safe to compare verbatim as reasons/values. */
    private Arbitrary<String> nonBlank() {
        return Arbitraries.strings().alpha().numeric().ofMinLength(1).ofMaxLength(24);
    }
}
