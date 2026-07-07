package com.adpilot.modules.apisync.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ApiSyncJobVo {

    private String id;
    private String connectionId;
    private String syncType;
    private String entityType;
    private String status;
    private Integer totalRecords;
    private Integer recordsProcessed;
    private Integer failedRecords;
    private String errorMessage;
    private String startedAt;
    private String completedAt;
    private String createdAt;
}
