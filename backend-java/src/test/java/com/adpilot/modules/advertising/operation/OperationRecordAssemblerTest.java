package com.adpilot.modules.advertising.operation;

import com.adpilot.modules.advertising.entity.OperationEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link OperationRecordAssembler}: audit-field completeness (Req 8.1), the
 * operationScope ↔ state-field separation invariant (Req 3.7/3.8), AI-decision completeness
 * (Req 49.9/22.10), and the canonical machine-value / JSON mapping.
 */
class OperationRecordAssemblerTest {

    private OperationRecordAssembler assembler;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        assembler = new OperationRecordAssembler(new OperationJsonCodec(mapper));
    }

    private OperationRecordCommand.OperationRecordCommandBuilder platformMutationBase() {
        return OperationRecordCommand.builder()
                .storeId(UUID.randomUUID())
                .operationSource(OperationSource.MANUAL)
                .operationScope(OperationScope.PLATFORM_MUTATION)
                .entityType("campaign")
                .entityId(UUID.randomUUID())
                .logicalOperationId(UUID.randomUUID())
                .logicalIdempotencyKey("op-key-1")
                .attemptId(UUID.randomUUID())
                .actingUserId(UUID.randomUUID())
                .syncState(SyncState.PENDING);
    }

    private OperationRecordCommand.OperationRecordCommandBuilder localConfigBase() {
        return OperationRecordCommand.builder()
                .storeId(UUID.randomUUID())
                .operationSource(OperationSource.MANUAL)
                .operationScope(OperationScope.LOCAL_CONFIGURATION)
                .entityType("ai_personality")
                .entityId(UUID.randomUUID())
                .logicalOperationId(UUID.randomUUID())
                .logicalIdempotencyKey("op-key-2")
                .attemptId(UUID.randomUUID())
                .actingUserId(UUID.randomUUID())
                .executionStatus(ExecutionStatus.APPLIED);
    }

    @Test
    void mapsPlatformMutationWithSyncStateAndNullExecutionStatus() {
        OperationEntity entity = assembler.toEntity(platformMutationBase()
                .beforeValue(new BigDecimal("1.20"))
                .afterValue(new BigDecimal("1.50"))
                .reversible(true)
                .build());

        assertThat(entity.getOperationSource()).isEqualTo("manual");
        assertThat(entity.getOperationScope()).isEqualTo("platform_mutation");
        assertThat(entity.getSyncState()).isEqualTo("pending");
        assertThat(entity.getExecutionStatus()).isNull();
        assertThat(entity.getBeforeValue()).isEqualTo("1.20");
        assertThat(entity.getAfterValue()).isEqualTo("1.50");
        assertThat(entity.getReversible()).isTrue();
        // defaults applied
        assertThat(entity.getAttemptNumber()).isEqualTo(1);
        assertThat(entity.getAffectedCount()).isEqualTo(1);
    }

    @Test
    void mapsLocalConfigurationWithExecutionStatusAndNullSyncState() {
        OperationEntity entity = assembler.toEntity(localConfigBase().build());

        assertThat(entity.getOperationScope()).isEqualTo("local_configuration");
        assertThat(entity.getExecutionStatus()).isEqualTo("applied");
        assertThat(entity.getSyncState()).isNull();
    }

    @Test
    void hyphenatedSyncStatesMapToCanonicalMachineValues() {
        assertThat(OperationMachineValues.toValue(SyncState.LOCAL_ONLY)).isEqualTo("local-only");
        assertThat(OperationMachineValues.toValue(SyncState.AMAZON_PROCESSING)).isEqualTo("amazon-processing");

        OperationEntity localOnly = assembler.toEntity(platformMutationBase()
                .syncState(SyncState.LOCAL_ONLY)
                .build());
        assertThat(localOnly.getSyncState()).isEqualTo("local-only");
    }

    @Test
    void rejectsPlatformMutationCarryingExecutionStatus() {
        assertThatThrownBy(() -> assembler.toEntity(platformMutationBase()
                .executionStatus(ExecutionStatus.APPLIED)
                .build()))
                .isInstanceOf(OperationRecordValidationException.class)
                .hasMessageContaining("executionStatus");
    }

    @Test
    void rejectsLocalConfigurationCarryingSyncState() {
        assertThatThrownBy(() -> assembler.toEntity(localConfigBase()
                .syncState(SyncState.PENDING)
                .build()))
                .isInstanceOf(OperationRecordValidationException.class)
                .hasMessageContaining("syncState");
    }

    @Test
    void rejectsPlatformMutationWithNoSyncState() {
        assertThatThrownBy(() -> assembler.toEntity(platformMutationBase()
                .syncState(null)
                .build()))
                .isInstanceOf(OperationRecordValidationException.class)
                .hasMessageContaining("syncState");
    }

    @Test
    void rejectsMissingRequiredAuditField() {
        assertThatThrownBy(() -> assembler.toEntity(platformMutationBase()
                .actingUserId(null)
                .build()))
                .isInstanceOf(OperationRecordValidationException.class)
                .hasMessageContaining("actingUserId");
    }

    @Test
    void requiresStatusReasonForFailedSyncState() {
        assertThatThrownBy(() -> assembler.toEntity(platformMutationBase()
                .syncState(SyncState.FAILED)
                .build()))
                .isInstanceOf(OperationRecordValidationException.class)
                .hasMessageContaining("statusReason");

        OperationEntity ok = assembler.toEntity(platformMutationBase()
                .syncState(SyncState.FAILED)
                .statusReason("platform rejected: invalid bid")
                .build());
        assertThat(ok.getStatusReason()).isEqualTo("platform rejected: invalid bid");
    }

    @Test
    void requiresStatusReasonForCancelledExecutionStatus() {
        assertThatThrownBy(() -> assembler.toEntity(localConfigBase()
                .executionStatus(ExecutionStatus.CANCELLED)
                .build()))
                .isInstanceOf(OperationRecordValidationException.class)
                .hasMessageContaining("statusReason");
    }

    @Test
    void requiresAiDecisionFieldsForAiHostingSource() {
        assertThatThrownBy(() -> assembler.toEntity(platformMutationBase()
                .operationSource(OperationSource.AI_HOSTING)
                .build()))
                .isInstanceOf(OperationRecordValidationException.class)
                .hasMessageContaining("personalityRuleVersion");

        AiDecision decision = AiDecision.builder()
                .triggerMetric("acos")
                .resolvedPersonality("balanced")
                .personalityAllowedMagnitude(new BigDecimal("0.10"))
                .appliedMagnitude(new BigDecimal("0.08"))
                .decisionReason("acos above target")
                .approvalRequired(false)
                .build();

        OperationEntity entity = assembler.toEntity(platformMutationBase()
                .operationSource(OperationSource.AI_HOSTING)
                .personalityRuleVersion("v3")
                .aiDecision(decision)
                .build());

        assertThat(entity.getOperationSource()).isEqualTo("ai_hosting");
        assertThat(entity.getPersonalityRuleVersion()).isEqualTo("v3");
        assertThat(entity.getAiDecision()).contains("\"triggerMetric\":\"acos\"");
        assertThat(entity.getAiDecision()).contains("\"resolvedPersonality\":\"balanced\"");
    }

    @Test
    void rejectsNullCommand() {
        assertThatThrownBy(() -> assembler.toEntity(null))
                .isInstanceOf(OperationRecordValidationException.class);
    }
}
