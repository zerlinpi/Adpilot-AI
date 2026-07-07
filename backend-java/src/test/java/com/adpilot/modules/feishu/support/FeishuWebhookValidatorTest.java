package com.adpilot.modules.feishu.support;

import com.adpilot.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link FeishuWebhookValidator}, the SSRF guard for Feishu/Lark
 * custom-bot webhook URLs. Constructed with the default allow-list
 * ({@code open.feishu.cn, open.larksuite.com}).
 */
class FeishuWebhookValidatorTest {

    private final FeishuWebhookValidator validator =
            new FeishuWebhookValidator("open.feishu.cn,open.larksuite.com");

    // ── accepted ─────────────────────────────────────────────────────────────

    @Test
    void acceptsValidFeishuHttpsUrl() {
        String url = "https://open.feishu.cn/open-apis/bot/v2/hook/abc123";
        assertThat(validator.isAllowedFeishuWebhook(url)).isTrue();
        // validate() must not throw for an allowed URL.
        validator.validate(url);
    }

    @Test
    void acceptsLarksuiteHostCaseInsensitively() {
        assertThat(validator.isAllowedFeishuWebhook(
                "https://OPEN.LARKSUITE.COM/open-apis/bot/v2/hook/x")).isTrue();
    }

    // ── rejected ─────────────────────────────────────────────────────────────

    @Test
    void rejectsNonAllowlistedHost() {
        assertThat(validator.isAllowedFeishuWebhook("https://evil.example.com/hook")).isFalse();
        assertThatThrownBy(() -> validator.validate("https://evil.example.com/hook"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode())
                        .isEqualTo("INVALID_WEBHOOK_URL"));
    }

    @Test
    void rejectsHttpScheme() {
        // Right host but plain http, not https.
        assertThat(validator.isAllowedFeishuWebhook("http://open.feishu.cn/hook")).isFalse();
        assertThatThrownBy(() -> validator.validate("http://open.feishu.cn/hook"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsLocalhost() {
        assertThat(validator.isAllowedFeishuWebhook("https://localhost:8080/hook")).isFalse();
        assertThat(validator.isAllowedFeishuWebhook("http://localhost/hook")).isFalse();
    }

    @Test
    void rejectsCloudMetadataIp() {
        assertThat(validator.isAllowedFeishuWebhook("https://169.254.169.254/latest/meta-data"))
                .isFalse();
        assertThatThrownBy(() -> validator.validate("https://169.254.169.254/"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsPrivateAndLoopbackIps() {
        assertThat(validator.isAllowedFeishuWebhook("https://127.0.0.1/hook")).isFalse();
        assertThat(validator.isAllowedFeishuWebhook("https://10.0.0.5/hook")).isFalse();
        assertThat(validator.isAllowedFeishuWebhook("https://172.16.0.1/hook")).isFalse();
        assertThat(validator.isAllowedFeishuWebhook("https://192.168.1.1/hook")).isFalse();
        assertThat(validator.isAllowedFeishuWebhook("https://[::1]/hook")).isFalse();
    }

    @Test
    void rejectsNullBlankAndMalformed() {
        assertThat(validator.isAllowedFeishuWebhook(null)).isFalse();
        assertThat(validator.isAllowedFeishuWebhook("")).isFalse();
        assertThat(validator.isAllowedFeishuWebhook("not a url")).isFalse();
    }

    @Test
    void selfHostedHostCanBeAllowlisted() {
        FeishuWebhookValidator custom =
                new FeishuWebhookValidator("open.feishu.cn,lark.internal.example.com");
        assertThat(custom.isAllowedFeishuWebhook("https://lark.internal.example.com/hook")).isTrue();
        // The default public host still works alongside the added one.
        assertThat(custom.isAllowedFeishuWebhook("https://open.feishu.cn/hook")).isTrue();
        // But a private IP is still rejected even if the allow-list is customized.
        assertThat(custom.isAllowedFeishuWebhook("https://127.0.0.1/hook")).isFalse();
    }
}
