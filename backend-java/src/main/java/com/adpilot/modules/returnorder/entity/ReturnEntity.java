package com.adpilot.modules.returnorder.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@TableName("returns")
@Table(name = "returns")
public class ReturnEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(name = "store_id", nullable = false, columnDefinition = "char(36)")
    private UUID storeId;

    @Column(name = "order_id", length = 255)
    private String orderId;

    @Column(name = "return_id", length = 255)
    private String returnId;

    @Column(name = "sku", length = 100)
    private String sku;

    @Column(name = "asin", length = 20)
    private String asin;

    @Column(name = "quantity_returned")
    @Builder.Default
    private Integer quantityReturned = 0;

    @Column(name = "return_reason", length = 255)
    private String returnReason;

    @Column(name = "return_status", length = 50)
    private String returnStatus;

    @Column(name = "return_date")
    private LocalDateTime returnDate;

    @Column(name = "refund_amount", precision = 18, scale = 4)
    @Builder.Default
    private BigDecimal refundAmount = BigDecimal.ZERO;

    @Column(name = "currency", length = 10)
    private String currency;

    @Column(name = "raw_data", columnDefinition = "json")
    @Builder.Default
    private String rawData = "{}";

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
