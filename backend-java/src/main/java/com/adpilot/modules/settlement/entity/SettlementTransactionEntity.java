package com.adpilot.modules.settlement.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@TableName("settlement_transactions")
@Table(name = "settlement_transactions")
public class SettlementTransactionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(name = "settlement_id", nullable = false, columnDefinition = "char(36)")
    private UUID settlementId;

    @Column(name = "order_id", length = 255)
    private String orderId;

    @Column(name = "sku", length = 100)
    private String sku;

    @Column(name = "transaction_type", length = 100)
    private String transactionType;

    @Column(name = "amount", precision = 18, scale = 4)
    @Builder.Default
    private BigDecimal amount = BigDecimal.ZERO;

    @Column(name = "fee_type", length = 100)
    private String feeType;

    @Column(name = "fee_amount", precision = 18, scale = 4)
    @Builder.Default
    private BigDecimal feeAmount = BigDecimal.ZERO;

    @Column(name = "currency", length = 10)
    private String currency;

    @Column(name = "raw_data", columnDefinition = "TEXT")
    private String rawData;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
