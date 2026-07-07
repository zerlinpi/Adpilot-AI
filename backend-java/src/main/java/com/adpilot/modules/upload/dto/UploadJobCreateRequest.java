package com.adpilot.modules.upload.dto;
import lombok.Data;

@Data
public class UploadJobCreateRequest {
    private String storeId;
    private String productId;
    private String marketplaceId;
    private String uploadMethod;
}
