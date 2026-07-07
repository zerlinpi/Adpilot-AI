package com.adpilot.modules.logistics.vo;

import lombok.Builder;
import lombok.Data;

/**
 * Response VO for a {@code Tracking_Event} (轨迹) entry.
 */
@Data
@Builder
public class TrackingEventVo {

    private String id;
    private String shipmentId;
    private String legId;
    private String eventTime;
    private String recordedAt;
    private String description;
    private String createdAt;
    private String updatedAt;
}
