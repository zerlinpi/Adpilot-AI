package com.adpilot.modules.apisync.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Per-(store, entity_type) sync watermark recording the point of the last
 * successfully synced record, used to retrieve only records created or changed
 * since the previous successful sync (Req 1.1.6-1.1.8).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@TableName("sync_watermarks")
@Table(name = "sync_watermarks")
public class SyncWatermarkEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(name = "store_id", nullable = false, columnDefinition = "char(36)")
    private UUID storeId;

    @Column(name = "entity_type", nullable = false, length = 50)
    private String entityType;

    @Column(name = "watermark_at")
    private LocalDateTime watermarkAt;

    @Column(name = "cursor_token", length = 512)
    private String cursorToken;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
