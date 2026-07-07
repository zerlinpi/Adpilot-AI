package com.adpilot.modules.feishu.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class FeishuNotificationRuleVo {

    private String id;
    private String feishuIntegrationId;
    private String storeId;
    private String chatId;
    private String name;
    private String eventType;
    private String conditionJson;
    private Boolean enabled;
    private String status;
    private String createdAt;
    private String updatedAt;
}
