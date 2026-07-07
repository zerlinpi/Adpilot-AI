package com.adpilot.modules.feishu.vo;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class FeishuIntegrationVo {

    private String id;
    private String orgId;
    private String storeId;
    private String provider;
    private String appId;
    private String connectionType;
    private String botOpenId;
    private String defaultChatId;
    private String status;
    private String lastConnectedAt;
    private String createdAt;
}
