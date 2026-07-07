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
 * Saved_View — a per-user, named, persisted combination of a table's column
 * configuration, filters, and sort order (stored in {@code config} JSON).
 *
 * <p>Mirrors {@code saved_views} in db/schema.sql: scoped by
 * {@code (user_id, table_key)} and store-independent, with a unique constraint
 * on {@code (user_id, table_key, name)} (Req 2.11, 2.12, 2.13, 17.5).</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@TableName("saved_views")
@Table(name = "saved_views")
public class SavedViewEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(name = "user_id", nullable = false, columnDefinition = "char(36)")
    private UUID userId;

    @Column(name = "table_key", nullable = false, length = 100)
    private String tableKey;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "config", columnDefinition = "json")
    private String config;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
