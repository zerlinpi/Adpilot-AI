package com.adpilot.modules.tableview.entity;

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
 * Column_Configuration — a per-user choice of which columns are visible and
 * their order within a table (stored in {@code config} JSON).
 *
 * <p>Mirrors {@code column_configs} in db/schema.sql: keyed by
 * {@code (user_id, table_key)} and store-independent, with a unique constraint
 * on {@code (user_id, table_key)} (Req 2.12, 17.5).</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@TableName("column_configs")
@Table(name = "column_configs")
public class ColumnConfigEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(name = "user_id", nullable = false, columnDefinition = "char(36)")
    private UUID userId;

    @Column(name = "table_key", nullable = false, length = 100)
    private String tableKey;

    @Column(name = "config", columnDefinition = "json")
    private String config;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
