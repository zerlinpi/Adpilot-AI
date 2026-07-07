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
 * Smart Diagnosis task (诊断任务) — a per-product (parent ASIN) diagnosis that
 * analyzes the product's ad structure and produces a diagnosis result (Req 22).
 * Each task carries an update frequency, a creator, a last-diagnosis time, and
 * a JSON diagnosis result.
 *
 * <p>Backed by the additive {@code smart_diagnosis_tasks} table created in
 * {@code V3__ai_workitems.sql}. Conventions mirror the other advertising
 * entities: {@code CHAR(36)} ids and JPA + MyBatis-Plus annotations.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@TableName("smart_diagnosis_tasks")
@Table(name = "smart_diagnosis_tasks")
public class SmartDiagnosisTaskEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(name = "store_id", nullable = false, columnDefinition = "char(36)")
    private UUID storeId;

    /** Parent ASIN (父ASIN) the diagnosis task targets. */
    @Column(name = "parent_asin", nullable = false, length = 20)
    private String parentAsin;

    /** Update frequency (更新频率): manual / daily / weekly. */
    @Column(name = "update_frequency", length = 20)
    @Builder.Default
    private String updateFrequency = "manual";

    /** Task status: pending / running / completed / failed. */
    @Column(name = "status", length = 20)
    @Builder.Default
    private String status = "pending";

    /** Diagnosis result as a JSON string (analysis of the ad structure). */
    @Column(name = "result_json", columnDefinition = "json")
    private String resultJson;

    @Column(name = "created_by", columnDefinition = "char(36)")
    private UUID createdBy;

    /** Time the diagnosis last completed (最近诊断时间). */
    @Column(name = "last_diagnosed_at")
    private LocalDateTime lastDiagnosedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
