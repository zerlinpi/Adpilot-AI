package com.adpilot.modules.store.entity;

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
@TableName("marketplaces")
@Table(name = "marketplaces")
public class MarketplaceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(name = "code", nullable = false, unique = true, length = 10)
    private String code;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "flag", length = 10)
    private String flag;

    /** IANA Marketplace_Timezone for day-boundary / scheduling computation (Req 51.12). */
    @Column(name = "timezone", length = 64)
    private String timezone;

    /** Whether this marketplace's jurisdiction is subject to VAT (Req 9.2.3). */
    @Column(name = "vat_applicable", nullable = false)
    private Boolean vatApplicable;

    /** VAT rate applied for this marketplace, e.g. 0.2000 for 20% (Req 9.2.3). */
    @Column(name = "vat_rate", precision = 6, scale = 4)
    private BigDecimal vatRate;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
