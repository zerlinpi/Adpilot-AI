package com.adpilot.modules.audit.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@TableName("ai_model_call_logs")
@Table(name = "ai_model_call_logs")
public class AiModelCallLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(name = "user_id", columnDefinition = "char(36)")
    private UUID userId;

    @Column(name = "store_id", columnDefinition = "char(36)")
    private UUID storeId;

    @Column(nullable = false, length = 100)
    private String feature;

    @Column(nullable = false, length = 100)
    private String model;

    @Column(name = "prompt_snapshot", columnDefinition = "TEXT")
    private String promptSnapshot;

    @Column(name = "input_snapshot", columnDefinition = "json")
    private String inputSnapshot;

    @Column(name = "output_snapshot", columnDefinition = "json")
    private String outputSnapshot;

    @Column(length = 30)
    @Builder.Default
    private String status = "success";

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
