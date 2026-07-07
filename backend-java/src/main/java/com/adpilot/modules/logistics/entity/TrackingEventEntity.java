package com.adpilot.modules.logistics.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Tracking Event (轨迹) — a timestamped trajectory entry for a shipment. The
 * {@code recordedAt} field is the tiebreaker for equal {@code eventTime}
 * values and {@code legId} is nullable. Mirrors {@code tracking_events} in
 * db/schema.sql.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@TableName("tracking_events")
@Table(name = "tracking_events")
public class TrackingEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(name = "shipment_id", nullable = false, columnDefinition = "char(36)")
    private UUID shipmentId;

    @Column(name = "leg_id", columnDefinition = "char(36)")
    private UUID legId;

    @Column(name = "event_time", nullable = false)
    private LocalDateTime eventTime;

    @Column(name = "recorded_at")
    private LocalDateTime recordedAt;

    @Column(name = "description", nullable = false, length = 500)
    private String description;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
