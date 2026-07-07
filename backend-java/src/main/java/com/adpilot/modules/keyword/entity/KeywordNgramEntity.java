package com.adpilot.modules.keyword.entity;

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
@TableName("keyword_ngrams")
@Table(name = "keyword_ngrams")
public class KeywordNgramEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(name = "store_id", nullable = false, columnDefinition = "char(36)")
    private UUID storeId;

    @Column(name = "ngram", length = 200)
    private String ngram;

    @Column(name = "ngram_type", length = 20)
    private String ngramType;

    @Column(name = "frequency")
    private Integer frequency;

    @Column(name = "total_clicks")
    private Long totalClicks;

    @Column(name = "total_orders")
    private Long totalOrders;

    @Column(name = "total_spend", precision = 12, scale = 2)
    private BigDecimal totalSpend;

    @Column(name = "total_sales", precision = 12, scale = 2)
    private BigDecimal totalSales;

    @Column(name = "avg_acos", precision = 8, scale = 4)
    private BigDecimal avgAcos;

    @Column(name = "avg_cvr", precision = 8, scale = 4)
    private BigDecimal avgCvr;

    @Column(name = "waste_count")
    private Integer wasteCount;

    @Column(name = "winner_count")
    private Integer winnerCount;

    @Column(name = "segment", length = 50)
    private String segment;

    @Column(name = "recommended_action", length = 100)
    private String recommendedAction;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
