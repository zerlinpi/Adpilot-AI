package com.adpilot.modules.advertising.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * acos_migration_exceptions — ACoS values whose stored scale is ambiguous and
 * cannot be safely converted to the decimal-ratio scale during migration
 * (Req 17.6). Such values are never guessed; they are recorded here for manual
 * review.
 *
 * <p>Backed by the {@code acos_migration_exceptions} table.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@TableName("acos_migration_exceptions")
@Table(name = "acos_migration_exceptions")
public class AcosMigrationExceptionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(name = "table_name", nullable = false, length = 100)
    private String tableName;

    @Column(name = "column_name", nullable = false, length = 100)
    private String columnName;

    @Column(name = "record_id", nullable = false, columnDefinition = "char(36)")
    private UUID recordId;

    @Column(name = "original_value", length = 255)
    private String originalValue;

    @Column(name = "reason", length = 500)
    private String reason;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
