package com.adpilot.modules.review.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ReviewAlertVo {

    private String id;
    private String storeId;
    private String reviewId;
    private String alertType;
    private String severity;
    private String message;
    private String status;
    private String assignedTo;
    private String resolvedAt;
    private String createdAt;
}
