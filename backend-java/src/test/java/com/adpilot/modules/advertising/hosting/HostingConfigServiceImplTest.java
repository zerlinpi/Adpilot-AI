package com.adpilot.modules.advertising.hosting;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.modules.advertising.dto.HostingConfigRequest;
import com.adpilot.modules.advertising.entity.SafetyBoundaryEntity;
import com.adpilot.modules.advertising.mapper.SafetyBoundaryMapper;
import com.adpilot.modules.advertising.support.SafetyBoundaryValidator;
import com.adpilot.modules.advertising.vo.HostingConfigVo;
import com.adpilot.modules.audit.service.AuditLogService;
import com.adpilot.modules.store.entity.StoreEntity;
import com.adpilot.modules.store.mapper.StoreMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link HostingConfigServiceImpl}.
 *
 * <p>Covers backend validation of personality / execution-mode / phase enums, the
 * {@code auto_execute_threshold} range, the only-tighten boundary rule, and the
 * save/load JSON round-trip.</p>
 *
 * <p>Validates: Requirements 21.2, 21.3, 21.4, 12.2, 12.6.</p>
 */
@DisplayName("HostingConfigServiceImpl")
class HostingConfigServiceImplTest {

    private HostingConfigMapper hostingConfigMapper;
    private StoreMapper storeMapper;
    private SafetyBoundaryMapper safetyBoundaryMapper;
    private AuditLogService auditLogService;
    private HostingConfigServiceImpl service;

    private UUID storeId;
    private UUID orgId;

    @BeforeEach
    void setUp() {
        hostingConfigMapper = mock(HostingConfigMapper.class);
        storeMapper = mock(StoreMapper.class);
        safetyBoundaryMapper = mock(SafetyBoundaryMapper.class);
        auditLogService = mock(AuditLogService.class);

        service = new HostingConfigServiceImpl(
                hostingConfigMapper, storeMapper, safetyBoundaryMapper,
                new SafetyBoundaryValidator(), auditLogService, new ObjectMapper());

        storeId = UUID.randomUUID();
        orgId = UUID.randomUUID();

        // Default: no existing config, store resolves to an org, no higher-level boundaries.
        when(hostingConfigMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(storeMapper.selectById(storeId)).thenReturn(
                StoreEntity.builder().id(storeId).orgId(orgId).build());
        when(safetyBoundaryMapper.findByScopeAndScopeId(eq("organization"), eq(orgId)))
                .thenReturn(List.of());
        when(safetyBoundaryMapper.findSystemBoundaries()).thenReturn(List.of());
    }

    @Nested
    @DisplayName("enum validation (Req 12.6)")
    class EnumValidation {

        @Test
        @DisplayName("rejects an invalid personality")
        void rejectsInvalidPersonality() {
            HostingConfigRequest req = HostingConfigRequest.builder()
                    .defaultPersonality("reckless")
                    .build();
            assertThatThrownBy(() -> service.saveStoreConfig(storeId, req, null))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", "HOSTING_INVALID_PERSONALITY");
        }

        @Test
        @DisplayName("accepts a valid personality (case-insensitive)")
        void acceptsValidPersonality() {
            HostingConfigRequest req = HostingConfigRequest.builder()
                    .defaultPersonality("Balanced")
                    .build();
            HostingConfigVo vo = service.saveStoreConfig(storeId, req, null);
            assertThat(vo.getDefaultPersonality()).isEqualTo("balanced");
        }

        @Test
        @DisplayName("rejects an invalid execution mode")
        void rejectsInvalidExecutionMode() {
            HostingConfigRequest req = HostingConfigRequest.builder()
                    .executionMode("yolo")
                    .build();
            assertThatThrownBy(() -> service.saveStoreConfig(storeId, req, null))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", "HOSTING_INVALID_EXECUTION_MODE");
        }

        @Test
        @DisplayName("rejects an invalid active phase")
        void rejectsInvalidPhase() {
            HostingConfigRequest req = HostingConfigRequest.builder()
                    .activePhase("V9")
                    .build();
            assertThatThrownBy(() -> service.saveStoreConfig(storeId, req, null))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", "HOSTING_INVALID_PHASE");
        }
    }

    @Nested
    @DisplayName("threshold validation (Req 21.3)")
    class ThresholdValidation {

        @Test
        @DisplayName("rejects threshold above 1.0")
        void rejectsAboveOne() {
            HostingConfigRequest req = HostingConfigRequest.builder()
                    .autoExecuteThreshold(new BigDecimal("1.5"))
                    .build();
            assertThatThrownBy(() -> service.saveStoreConfig(storeId, req, null))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", "HOSTING_INVALID_THRESHOLD");
        }

        @Test
        @DisplayName("rejects threshold below 0.0")
        void rejectsBelowZero() {
            HostingConfigRequest req = HostingConfigRequest.builder()
                    .autoExecuteThreshold(new BigDecimal("-0.1"))
                    .build();
            assertThatThrownBy(() -> service.saveStoreConfig(storeId, req, null))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", "HOSTING_INVALID_THRESHOLD");
        }

        @Test
        @DisplayName("accepts threshold within [0,1] (inclusive bounds)")
        void acceptsInRange() {
            for (String v : new String[]{"0.0", "0.5", "1.0"}) {
                HostingConfigRequest req = HostingConfigRequest.builder()
                        .autoExecuteThreshold(new BigDecimal(v))
                        .build();
                HostingConfigVo vo = service.saveStoreConfig(storeId, req, null);
                assertThat(vo.getAutoExecuteThreshold()).isEqualByComparingTo(v);
            }
        }
    }

    @Nested
    @DisplayName("boundary validation (Req 21.4)")
    class BoundaryValidation {

        @Test
        @DisplayName("rejects an unknown boundary limit name")
        void rejectsUnknownLimit() {
            HostingConfigRequest req = HostingConfigRequest.builder()
                    .boundaryOverrides(Map.of("NOT_A_LIMIT", new BigDecimal("1")))
                    .build();
            assertThatThrownBy(() -> service.saveStoreConfig(storeId, req, null))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", "HOSTING_INVALID_BOUNDARY");
        }

        @Test
        @DisplayName("rejects a store override that loosens a higher-level upper-bound limit")
        void rejectsLooseningUpperBound() {
            // Org caps MAX_BID at 2.0; store proposes 3.0 which is looser → reject.
            when(safetyBoundaryMapper.findByScopeAndScopeId(eq("organization"), eq(orgId)))
                    .thenReturn(List.of(boundaryRow("MAX_BID", "upper_bound", new BigDecimal("2.0"))));

            HostingConfigRequest req = HostingConfigRequest.builder()
                    .boundaryOverrides(Map.of("MAX_BID", new BigDecimal("3.0")))
                    .build();

            assertThatThrownBy(() -> service.saveStoreConfig(storeId, req, null))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", "HOSTING_BOUNDARY_VIOLATION");
        }

        @Test
        @DisplayName("accepts a store override that tightens a higher-level upper-bound limit")
        void acceptsTighteningUpperBound() {
            when(safetyBoundaryMapper.findByScopeAndScopeId(eq("organization"), eq(orgId)))
                    .thenReturn(List.of(boundaryRow("MAX_BID", "upper_bound", new BigDecimal("2.0"))));

            HostingConfigRequest req = HostingConfigRequest.builder()
                    .boundaryOverrides(Map.of("MAX_BID", new BigDecimal("1.5")))
                    .build();

            HostingConfigVo vo = service.saveStoreConfig(storeId, req, null);
            assertThat(vo.getBoundaryOverrides()).containsEntry("MAX_BID", new BigDecimal("1.5"));
        }
    }

    @Nested
    @DisplayName("save / load round-trip (Req 12.2)")
    class RoundTrip {

        @Test
        @DisplayName("saveStoreConfig persists and returns the supplied values")
        void saveReturnsValues() {
            HostingConfigRequest req = HostingConfigRequest.builder()
                    .activePhase("V2")
                    .defaultPersonality("aggressive")
                    .executionMode("approval_required")
                    .autoExecuteThreshold(new BigDecimal("0.8"))
                    .shadowMode(true)
                    .emergencyAutoActionEnabled(false)
                    .build();

            HostingConfigVo vo = service.saveStoreConfig(storeId, req, null);

            assertThat(vo.getActivePhase()).isEqualTo("V2");
            assertThat(vo.getDefaultPersonality()).isEqualTo("aggressive");
            assertThat(vo.getExecutionMode()).isEqualTo("approval_required");
            assertThat(vo.getAutoExecuteThreshold()).isEqualByComparingTo("0.8");
            assertThat(vo.getShadowMode()).isTrue();
            assertThat(vo.getEmergencyAutoActionEnabled()).isFalse();

            // An insert is performed because no prior config existed.
            verify(hostingConfigMapper).insert(any(HostingConfigEntity.class));
        }

        @Test
        @DisplayName("persisted JSON uses snake_case keys compatible with existing readers")
        void persistedJsonUsesSnakeCaseKeys() {
            HostingConfigRequest req = HostingConfigRequest.builder()
                    .activePhase("V3")
                    .shadowMode(true)
                    .build();

            service.saveStoreConfig(storeId, req, null);

            ArgumentCaptor<HostingConfigEntity> captor = ArgumentCaptor.forClass(HostingConfigEntity.class);
            verify(hostingConfigMapper).insert(captor.capture());
            String json = captor.getValue().getConfig();

            assertThat(json).contains("\"active_phase\":\"V3\"");
            assertThat(json).contains("\"shadow_mode\":true");
            // Compatible with the lightweight readers used elsewhere.
            assertThat(PhaseConfigurationServiceImpl.extractPhaseFromConfig(json).name()).isEqualTo("V3");
            assertThat(ShadowModeServiceImpl.extractShadowMode(json)).isTrue();
        }

        @Test
        @DisplayName("getConfig parses a stored JSON payload")
        void getConfigParsesStoredJson() {
            HostingConfigEntity entity = HostingConfigEntity.builder()
                    .id(UUID.randomUUID())
                    .storeId(storeId)
                    .scope("store")
                    .scopeId(storeId)
                    .config("{\"active_phase\":\"V2\",\"execution_mode\":\"auto_execute\","
                            + "\"auto_execute_threshold\":0.75,\"shadow_mode\":false}")
                    .build();
            when(hostingConfigMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(entity);

            HostingConfigVo vo = service.getConfig(storeId, "store", storeId);

            assertThat(vo.getActivePhase()).isEqualTo("V2");
            assertThat(vo.getExecutionMode()).isEqualTo("auto_execute");
            assertThat(vo.getAutoExecuteThreshold()).isEqualByComparingTo("0.75");
            assertThat(vo.getShadowMode()).isFalse();
        }

        @Test
        @DisplayName("getConfig returns null value fields when no config row exists")
        void getConfigReturnsEmptyWhenAbsent() {
            HostingConfigVo vo = service.getConfig(storeId, "store", storeId);
            assertThat(vo.getActivePhase()).isNull();
            assertThat(vo.getExecutionMode()).isNull();
            assertThat(vo.getScope()).isEqualTo("store");
            assertThat(vo.getStoreId()).isEqualTo(storeId.toString());
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Helpers
    // ────────────────────────────────────────────────────────────────────────────

    private static SafetyBoundaryEntity boundaryRow(String limitType, String semantics, BigDecimal ratioOrAmount) {
        return SafetyBoundaryEntity.builder()
                .id(UUID.randomUUID())
                .scope("organization")
                .limitType(limitType)
                .valueType("amount")
                .valueAmount(ratioOrAmount)
                .comparisonSemantics(semantics)
                .build();
    }
}
