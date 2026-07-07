package com.adpilot.modules.tableview.vo;

import lombok.Builder;
import lombok.Data;

/**
 * Response VO for a {@code Column_Configuration}.
 */
@Data
@Builder
public class ColumnConfigVo {

    private String id;
    private String userId;
    private String tableKey;
    private String config;
    private String createdAt;
    private String updatedAt;
}
