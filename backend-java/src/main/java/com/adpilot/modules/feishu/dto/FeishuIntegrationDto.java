package com.adpilot.modules.feishu.dto;

import lombok.Data;

@Data
public class FeishuIntegrationDto {

    /** Optional — if omitted, the backend resolves from the authenticated user's org. */
    private String orgId;

    private String storeId;

    private String provider;

    /** Connection type: "app" (App ID + App Secret) or "webhook". */
    private String connectionType;

    private String appId;

    /** Plain-text App Secret — encrypted by the service before persistence. */
    private String appSecret;

    /** Legacy field kept for backward compatibility (already-encrypted value). */
    private String appSecretEncrypted;

    private String verificationTokenEncrypted;

    private String encryptKeyEncrypted;

    private String botOpenId;

    private String defaultChatId;
}
