package com.adpilot.modules.scheduler.vo;

import lombok.Builder;
import lombok.Data;

/**
 * View of a sync schedule for the management UI (Req 4.2.1): exposes the enabled
 * state, last run time, and next run time alongside its identity and recurrence.
 */
@Data
@Builder
public class SyncScheduleVo {

    private String id;
    private String connectionId;
    private String jobType;
    private String cronExpression;
    private Boolean enabled;
    private String lastRunAt;
    private String nextRunAt;
}
