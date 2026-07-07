package com.adpilot.modules.upload.vo;
import lombok.Builder;
import lombok.Data;

@Data @Builder
public class UploadJobVo {
    private String id;
    private String storeId;
    private String productId;
    private String marketplaceId;
    private String uploadMethod;
    private String status;
    private String errorMessage;
    private String createdAt;
    private String updatedAt;
}
