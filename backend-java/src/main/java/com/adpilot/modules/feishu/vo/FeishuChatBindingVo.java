package com.adpilot.modules.feishu.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class FeishuChatBindingVo {

    private String id;
    private String feishuIntegrationId;
    private String storeId;
    private String chatId;
    private String chatType;
    private String chatName;
    private Boolean notifyOnApproval;
    private Boolean notifyOnExecution;
    private Boolean notifyOnRollback;
    private Boolean notifyOnRiskAlert;
    private String status;
    private String createdAt;
    private String updatedAt;
}
