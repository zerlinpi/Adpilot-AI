package com.adpilot.modules.upload.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.UUID;

@Data
@Entity
@TableName("product_upload_jobs")
@Table(name = "product_upload_jobs")
public class ProductUploadJobEntity {
    @Id @GeneratedValue(strategy = GenerationType.UUID) @Column(columnDefinition = "char(36)") private UUID id;
    @Column(name = "store_id", columnDefinition = "char(36)") private UUID storeId;
    @Column(name = "product_id", columnDefinition = "char(36)") private UUID productId;
    @Column(name = "marketplace_id", columnDefinition = "char(36)") private UUID marketplaceId;
    @Column(name = "upload_method") private String uploadMethod;
    private String status;
    @Column(columnDefinition = "json") private String payload;
    @Column(columnDefinition = "json") private String response;
    @Column(name = "error_message") private String errorMessage;
    @Column(name = "created_by", columnDefinition = "char(36)") private UUID createdBy;
    @Column(name = "created_at") private LocalDateTime createdAt;
    @Column(name = "updated_at") private LocalDateTime updatedAt;
}
