package com.adpilot.modules.dataquality.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class DataQualityIssueVo {

    private String id;
    private String storeId;
    private String importJobId;
    private String severity;
    private String issueType;
    private String title;
    private String description;
    private String relatedEntityType;
    private String relatedEntityId;
    private String status;
    private String createdAt;
}
