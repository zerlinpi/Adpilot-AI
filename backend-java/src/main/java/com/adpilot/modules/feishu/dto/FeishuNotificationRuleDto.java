package com.adpilot.modules.feishu.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class FeishuNotificationRuleDto {

    private String storeId;

    private String chatId;

    @NotBlank(message = "Rule name is required")
    private String name;

    @NotBlank(message = "Event type is required")
    private String eventType;

    private String conditionJson;

    private Boolean enabled;
}
