package com.adpilot.modules.importcenter.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ImportJobVo {

    private String id;
    private String storeId;
    private String marketplaceId;
    private String reportType;
    private String fileName;
    private long fileSize;
    private String status;
    private int totalRows;
    private int validRows;
    private int invalidRows;
    private int duplicateRows;
    private String errorMessage;
    private String createdAt;
    private String updatedAt;
}
