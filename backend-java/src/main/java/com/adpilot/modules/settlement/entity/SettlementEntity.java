package com.adpilot.modules.settlement.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@TableName("settlements")
@Table(name = "settlements")
public class SettlementEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(name = "store_id", nullable = false, columnDefinition = "char(36)")
    private UUID storeId;

    @Column(name = "settlement_id", length = 255)
    private String settlementId;

    @Column(name = "settlement_start_date")
    private LocalDate settlementStartDate;

    @Column(name = "settlement_end_date")
    private LocalDate settlementEndDate;

    @Column(name = "deposit_date")
    private LocalDate depositDate;

    @Column(name = "total_amount", precision = 18, scale = 4)
    @Builder.Default
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @Column(name = "currency", length = 10)
    private String currency;

    @Column(name = "status", length = 30)
    @Builder.Default
    private String status = "pending";

    /** Reporting currency the amount was normalized to (Req 9.2.2). */
    @Column(name = "reporting_currency", length = 10)
    private String reportingCurrency;

    /** Amount converted to the reporting currency (Req 9.2.2). */
    @Column(name = "converted_amount", precision = 18, scale = 4)
    private BigDecimal convertedAmount;

    /** Exchange rate used for the conversion (Req 9.2.6). */
    @Column(name = "exchange_rate", precision = 18, scale = 8)
    private BigDecimal exchangeRate;

    /** Effective date of the exchange rate used (Req 9.2.6). */
    @Column(name = "rate_effective_date")
    private LocalDate rateEffectiveDate;

    /**
     * Reconciliation outcome (Req 9.1.3): {@code null} when not yet reconciled,
     * {@code "matched"} when within tolerance, {@code "discrepancy"} otherwise.
     */
    @Column(name = "reconciliation_status", length = 20)
    private String reconciliationStatus;

    @Column(name = "raw_data", columnDefinition = "TEXT")
    private String rawData;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
