package com.adpilot.modules.settlement.service.impl;

import com.adpilot.common.api.PageResponse;
import com.adpilot.common.exception.BusinessException;
import com.adpilot.modules.settlement.dto.SettlementDto;
import com.adpilot.modules.settlement.entity.SettlementEntity;
import com.adpilot.modules.settlement.mapper.SettlementMapper;
import com.adpilot.modules.settlement.vo.SettlementVo;
import com.adpilot.modules.audit.service.AuditLogService;
import com.adpilot.common.security.DataScopeService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SettlementServiceImpl} — the settlement import + read service.
 *
 * <p>Covers the import happy path (money math preserved through the entity, defaults applied),
 * paginated listing with money aggregation across rows, the not-found read edge case, and the
 * store-filter behavior (a blank / malformed store id is tolerated rather than throwing).
 *
 * <p>These tests exercise the service through its public API with a mocked {@link SettlementMapper}
 * so no database is required.
 */
@DisplayName("SettlementServiceImpl unit tests")
class SettlementServiceImplTest {

    private SettlementMapper settlementMapper;
    private AuditLogService auditLogService;
    private SettlementServiceImpl service;

    private static final String STORE_ID = UUID.randomUUID().toString();

    @BeforeEach
    void setUp() {
        settlementMapper = mock(SettlementMapper.class);
        auditLogService = mock(AuditLogService.class);
        service = new SettlementServiceImpl(settlementMapper, mock(DataScopeService.class), auditLogService);
    }

    // ---------------------------------------------------------------------------------------------
    // importSettlement
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("importSettlement: happy path persists the row and returns the mapped VO with exact money value")
    void importHappyPath() {
        SettlementDto dto = new SettlementDto();
        dto.setStoreId(STORE_ID);
        dto.setSettlementId("SETTLE-001");
        dto.setSettlementStartDate(LocalDate.of(2024, 1, 1));
        dto.setSettlementEndDate(LocalDate.of(2024, 1, 15));
        dto.setDepositDate(LocalDate.of(2024, 1, 20));
        dto.setTotalAmount(new BigDecimal("1234.5678"));
        dto.setCurrency("USD");
        dto.setStatus("settled");
        dto.setRawData("{\"k\":\"v\"}");

        // Simulate the DB assigning an id + timestamps on insert.
        doAnswer(inv -> {
            SettlementEntity e = inv.getArgument(0);
            e.setId(UUID.randomUUID());
            e.setCreatedAt(LocalDateTime.of(2024, 1, 20, 10, 30, 0));
            e.setUpdatedAt(LocalDateTime.of(2024, 1, 20, 10, 30, 0));
            return 1;
        }).when(settlementMapper).insert(any(SettlementEntity.class));

        SettlementVo vo = service.importSettlement(dto);

        // The entity handed to the mapper carries the exact money value and the DTO fields.
        ArgumentCaptor<SettlementEntity> captor = ArgumentCaptor.forClass(SettlementEntity.class);
        verify(settlementMapper).insert(captor.capture());
        SettlementEntity persisted = captor.getValue();
        assertThat(persisted.getStoreId()).isEqualTo(UUID.fromString(STORE_ID));
        assertThat(persisted.getSettlementId()).isEqualTo("SETTLE-001");
        assertThat(persisted.getTotalAmount()).isEqualByComparingTo(new BigDecimal("1234.5678"));
        assertThat(persisted.getCurrency()).isEqualTo("USD");
        assertThat(persisted.getStatus()).isEqualTo("settled");

        // The returned VO reflects the persisted values.
        assertThat(vo.getSettlementId()).isEqualTo("SETTLE-001");
        assertThat(vo.getTotalAmount()).isEqualTo(1234.5678);
        assertThat(vo.getCurrency()).isEqualTo("USD");
        assertThat(vo.getStatus()).isEqualTo("settled");
        assertThat(vo.getSettlementStartDate()).isEqualTo("2024-01-01");
        assertThat(vo.getSettlementEndDate()).isEqualTo("2024-01-15");
        assertThat(vo.getDepositDate()).isEqualTo("2024-01-20");
    }

    @Test
    @DisplayName("importSettlement: null total amount defaults to zero and null status defaults to 'pending'")
    void importAppliesDefaults() {
        SettlementDto dto = new SettlementDto();
        dto.setStoreId(STORE_ID);
        dto.setSettlementId("SETTLE-EMPTY");
        dto.setTotalAmount(null);
        dto.setStatus(null);
        dto.setCurrency("EUR");

        doAnswer(inv -> {
            ((SettlementEntity) inv.getArgument(0)).setId(UUID.randomUUID());
            return 1;
        }).when(settlementMapper).insert(any(SettlementEntity.class));

        SettlementVo vo = service.importSettlement(dto);

        ArgumentCaptor<SettlementEntity> captor = ArgumentCaptor.forClass(SettlementEntity.class);
        verify(settlementMapper).insert(captor.capture());
        assertThat(captor.getValue().getTotalAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(captor.getValue().getStatus()).isEqualTo("pending");
        assertThat(vo.getTotalAmount()).isEqualTo(0.0);
        assertThat(vo.getStatus()).isEqualTo("pending");
    }

    @Test
    @DisplayName("importSettlement: writes a forensic audit log with the settlement id and safe details")
    void importWritesAuditLog() {
        SettlementDto dto = new SettlementDto();
        dto.setStoreId(STORE_ID);
        dto.setSettlementId("SETTLE-AUDIT");
        dto.setSettlementStartDate(LocalDate.of(2024, 3, 1));
        dto.setSettlementEndDate(LocalDate.of(2024, 3, 31));
        dto.setTotalAmount(new BigDecimal("999.99"));
        dto.setCurrency("USD");
        dto.setStatus("settled");

        UUID generatedId = UUID.randomUUID();
        doAnswer(inv -> {
            ((SettlementEntity) inv.getArgument(0)).setId(generatedId);
            return 1;
        }).when(settlementMapper).insert(any(SettlementEntity.class));

        service.importSettlement(dto);

        // The audit log is written AFTER the successful insert, keyed to the settlement entity.
        verify(auditLogService).createLog(
                any(), any(), eq("IMPORT_SETTLEMENT"), eq("settlement"), eq(generatedId), any());
    }

    // ---------------------------------------------------------------------------------------------
    // listSettlements — aggregation / summation correctness across rows
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("listSettlements: sum of returned VO amounts equals the sum of the underlying entity amounts")
    void listPreservesMoneyMathAcrossRows() {
        List<SettlementEntity> rows = List.of(
                settlement("S1", new BigDecimal("100.10")),
                settlement("S2", new BigDecimal("200.20")),
                settlement("S3", new BigDecimal("0.05")),
                settlement("S4", new BigDecimal("-50.15")));
        stubSelectPage(rows, rows.size());

        PageResponse<SettlementVo> page = service.listSettlements(STORE_ID, 1, 20);

        assertThat(page.getItems()).hasSize(4);
        assertThat(page.getTotal()).isEqualTo(4);

        // The aggregate of the mapped VO amounts must equal the aggregate of the source rows.
        BigDecimal expectedTotal = rows.stream()
                .map(SettlementEntity::getTotalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        double actualTotal = page.getItems().stream()
                .mapToDouble(SettlementVo::getTotalAmount)
                .sum();
        assertThat(actualTotal).isEqualTo(expectedTotal.doubleValue());
    }

    @Test
    @DisplayName("listSettlements: empty result returns an empty page with total zero")
    void listEmpty() {
        stubSelectPage(new ArrayList<>(), 0);

        PageResponse<SettlementVo> page = service.listSettlements(STORE_ID, 1, 20);

        assertThat(page.getItems()).isEmpty();
        assertThat(page.getTotal()).isEqualTo(0);
    }

    @Test
    @DisplayName("listSettlements: a malformed store id is tolerated (no filter applied, no throw)")
    void listMalformedStoreIdTolerated() {
        stubSelectPage(new ArrayList<>(), 0);

        // Must not throw despite the non-UUID store id.
        PageResponse<SettlementVo> page = service.listSettlements("not-a-uuid", 1, 20);
        assertThat(page.getItems()).isEmpty();
    }

    // ---------------------------------------------------------------------------------------------
    // getSettlementById
    // ---------------------------------------------------------------------------------------------

    @Test
    @DisplayName("getSettlementById: returns the mapped VO for an existing settlement")
    void getByIdFound() {
        SettlementEntity entity = settlement("S-FOUND", new BigDecimal("42.4200"));
        UUID id = entity.getId();
        when(settlementMapper.selectById(id)).thenReturn(entity);

        SettlementVo vo = service.getSettlementById(id.toString());

        assertThat(vo.getSettlementId()).isEqualTo("S-FOUND");
        assertThat(vo.getTotalAmount()).isEqualTo(42.42);
    }

    @Test
    @DisplayName("getSettlementById: missing settlement throws a not-found BusinessException")
    void getByIdNotFound() {
        UUID id = UUID.randomUUID();
        when(settlementMapper.selectById(id)).thenReturn(null);

        assertThatThrownBy(() -> service.getSettlementById(id.toString()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("not found");
        verify(settlementMapper, never()).insert(any());
    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private void stubSelectPage(List<SettlementEntity> records, long total) {
        when(settlementMapper.selectPage(any(IPage.class), any(QueryWrapper.class)))
                .thenAnswer(inv -> {
                    Page<SettlementEntity> p = inv.getArgument(0);
                    p.setRecords(records);
                    p.setTotal(total);
                    return p;
                });
    }

    private SettlementEntity settlement(String settlementId, BigDecimal amount) {
        return SettlementEntity.builder()
                .id(UUID.randomUUID())
                .storeId(UUID.fromString(STORE_ID))
                .settlementId(settlementId)
                .settlementStartDate(LocalDate.of(2024, 1, 1))
                .settlementEndDate(LocalDate.of(2024, 1, 31))
                .totalAmount(amount)
                .currency("USD")
                .status("settled")
                .createdAt(LocalDateTime.of(2024, 2, 1, 0, 0))
                .updatedAt(LocalDateTime.of(2024, 2, 1, 0, 0))
                .build();
    }
}
