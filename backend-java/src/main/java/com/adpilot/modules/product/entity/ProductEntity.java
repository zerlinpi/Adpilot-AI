package com.adpilot.modules.product.entity;

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
@TableName("products")
@Table(name = "products")
public class ProductEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(name = "store_id", nullable = false, columnDefinition = "char(36)")
    private UUID storeId;

    @Column(name = "sku", nullable = false, length = 100)
    private String sku;

    @Column(name = "asin", length = 20)
    private String asin;

    @Column(name = "name", nullable = false, length = 500)
    private String name;

    @Column(name = "image_url", columnDefinition = "TEXT")
    private String imageUrl;

    @Column(name = "price", precision = 18, scale = 4)
    @Builder.Default
    private BigDecimal price = BigDecimal.ZERO;

    @Column(name = "cost", precision = 18, scale = 4)
    @Builder.Default
    private BigDecimal cost = BigDecimal.ZERO;

    @Column(name = "gross_margin", precision = 10, scale = 6)
    @Builder.Default
    private BigDecimal grossMargin = BigDecimal.ZERO;

    @Column(name = "inventory")
    @Builder.Default
    private Integer inventory = 0;

    @Column(name = "target_acos", precision = 10, scale = 6)
    @Builder.Default
    private BigDecimal targetAcos = BigDecimal.ZERO;

    @Column(name = "break_even_acos", precision = 10, scale = 6)
    @Builder.Default
    private BigDecimal breakEvenAcos = BigDecimal.ZERO;

    @Column(name = "category", length = 255)
    private String category;

    @Column(name = "brand", length = 255)
    private String brand;

    @Column(name = "status", length = 20)
    @Builder.Default
    private String status = "active";

    @Column(name = "created_by", columnDefinition = "char(36)")
    private UUID createdBy;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_by", columnDefinition = "char(36)")
    private UUID updatedBy;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
