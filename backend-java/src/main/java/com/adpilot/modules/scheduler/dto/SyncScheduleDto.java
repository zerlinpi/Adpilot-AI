package com.adpilot.modules.scheduler.dto;

import lombok.Data;

/**
 * Request payload to create or update a sync schedule (Req 4.1.4). When {@code id}
 * is null a new schedule is created; otherwise the matching schedule is updated.
 */
@Data
public class SyncScheduleDto {

    private String id;
    private String connectionId;
    private String jobType;
    private String cronExpression;
    private Boolean enabled;
}
