package com.adpilot.modules.importcenter.dto;

import lombok.Data;

@Data
public class ImportUploadRequest {

    private String storeId;
    private String marketplaceId;
    private String reportType;
    private String fileName;
    private String csvContent;
}
