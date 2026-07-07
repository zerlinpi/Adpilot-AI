package com.adpilot.modules.apisync.vo;

import lombok.Builder;
import lombok.Data;

/**
 * View of a single record-level sync log entry (Req 1.3.4/1.3.5). Each entry
 * carries the level, a short message, and detail text (such as the failing
 * record's error) so operators can diagnose a job.
 */
@Data
@Builder
public class ApiSyncLogVo {

    private String id;
    private String jobId;
    private String level;
    private String message;
    private String details;
    private String createdAt;
    private String timestamp;
}
