package com.adpilot.modules.audit.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AiModelCallLogVo {

    private String id;
    private String userId;
    private String storeId;
    private String feature;
    private String model;
    private String promptSnapshot;
    private String inputSnapshot;
    private String outputSnapshot;
    private String status;
    private String errorMessage;
    private String createdAt;
}
