package com.adpilot.modules.feishu.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Quick-connect a Feishu custom-bot webhook. Webhook connections carry no
 * app credentials — only the incoming-webhook URL and an optional signing
 * secret. Both URL and secret are encrypted at rest via {@code CryptoUtil}.
 */
@Data
public class FeishuWebhookConnectDto {

    private String orgId;

    private String storeId;

    private String name;

    @NotBlank(message = "Webhook URL is required")
    private String webhookUrl;

    private String webhookSecret;
}
