package com.adpilot.modules.advertising.hosting;

import com.baomidou.mybatisplus.annotation.TableName;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Entity for the {@code hosting_configs} table (Req 12.1, 21.1).
 *
 * <p>Each row represents a hosting configuration at a particular scope
 * (store, goal, or campaign). The {@code config} column holds a JSON payload
 * containing the configuration map, including fields like {@code execution_mode},
 * {@code personality}, thresholds, etc.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@TableName("hosting_configs")
@Table(name = "hosting_configs")
public class HostingConfigEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(name = "store_id", nullable = false, columnDefinition = "char(36)")
    private UUID storeId;

    /** Scope type: 'store', 'goal', or 'campaign'. */
    @Column(name = "scope", nullable = false, length = 12)
    private String scope;

    /** The id of the scoped entity (store id, goal id, or campaign id). */
    @Column(name = "scope_id", nullable = false, columnDefinition = "char(36)")
    private UUID scopeId;

    /** JSON payload containing the hosting configuration. */
    @Column(name = "config", columnDefinition = "json")
    private String config;

    @Column(name = "created_by", columnDefinition = "char(36)")
    private UUID createdBy;

    @Column(name = "updated_by", columnDefinition = "char(36)")
    private UUID updatedBy;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
