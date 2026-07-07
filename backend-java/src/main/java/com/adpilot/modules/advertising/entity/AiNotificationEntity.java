package com.adpilot.modules.advertising.entity;

import com.baomidou.mybatisplus.annotation.TableField;
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
 * AI Notification work item (Req 23) — a notification the AI advertising module
 * raises for operator attention in one of four categories
 * ({@code core_ops|one_click_optimize|high_potential|target_correction}). It
 * lives in a {@code pending}(待处理) or {@code closed}(已结束) state and, once
 * closed, carries a {@code resolution} ({@code applied|confirmed|rejected|dismissed}).
 *
 * <p>Backed by the additive {@code ai_notifications} table created in
 * {@code V3__ai_workitems.sql}. The {@code open_dedup_key} column is generated
 * by the database (populated only while pending) and is therefore read-only
 * here — never written by the application.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@TableName("ai_notifications")
@Table(name = "ai_notifications")
public class AiNotificationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(name = "store_id", nullable = false, columnDefinition = "char(36)")
    private UUID storeId;

    /** Category: core_ops | one_click_optimize | high_potential | target_correction. */
    @Column(name = "category", nullable = false, length = 40)
    private String category;

    @Column(name = "title", nullable = false, length = 255)
    private String title;

    /** Structured payload (proposed change, affected object, metrics) as a JSON string. */
    @Column(name = "detail_json", columnDefinition = "json")
    private String detailJson;

    /** Campaign / target id the item concerns (subject of the dedup key). */
    @Column(name = "subject_id", length = 255)
    private String subjectId;

    /** Lifecycle state: pending(待处理) | closed(已结束). */
    @Column(name = "state", nullable = false, length = 20)
    @Builder.Default
    private String state = "pending";

    /** Disposition once closed: applied | confirmed | rejected | dismissed. */
    @Column(name = "resolution", length = 20)
    private String resolution;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    // Note: the database-generated `open_dedup_key` column (populated only while
    // pending, NULL once closed) is intentionally NOT mapped here. It is owned
    // and computed by the database; mapping it would risk the application
    // attempting to write the generated column on insert/update.
}
