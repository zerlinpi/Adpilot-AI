package com.adpilot.modules.feishu.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class FeishuChatBindingDto {

    private String storeId;

    @NotBlank(message = "Chat ID is required")
    private String chatId;

    private String chatType;

    private String chatName;

    private Boolean notifyOnApproval;

    private Boolean notifyOnExecution;

    private Boolean notifyOnRollback;

    private Boolean notifyOnRiskAlert;
}
