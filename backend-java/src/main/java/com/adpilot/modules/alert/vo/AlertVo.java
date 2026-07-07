package com.adpilot.modules.alert.vo;

import lombok.Builder;
import lombok.Data;

/**
 * Alert-center view object returned to clients (Req 10.1.5).
 */
@Data
@Builder
public class AlertVo {

    private String id;
    private String storeId;
    private String alertType;
    private String subjectId;
    private String severity;
    private String status;
    private String message;
    private Boolean feishuPushed;
    private String feishuError;
    private String firstSeenAt;
    private String lastSeenAt;
    private String resolvedAt;
}
