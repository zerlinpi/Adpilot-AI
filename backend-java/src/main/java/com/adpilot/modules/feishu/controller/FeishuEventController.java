package com.adpilot.modules.feishu.controller;

import com.adpilot.common.utils.CryptoUtil;
import com.adpilot.modules.feishu.entity.FeishuChatBindingEntity;
import com.adpilot.modules.feishu.entity.FeishuIntegrationEntity;
import com.adpilot.modules.feishu.mapper.FeishuChatBindingMapper;
import com.adpilot.modules.feishu.mapper.FeishuIntegrationMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Receives Feishu event-subscription callbacks (enterprise self-built app).
 *
 * <p>Public endpoint (permitted in SecurityConfig) — Feishu's servers call it.
 * It handles the one-time {@code url_verification} challenge and the
 * {@code im.chat.member.bot.added_v1} event, which auto-binds the chat to the
 * integration so operators don't have to copy chat ids ("add bot to group =
 * connected" experience).</p>
 *
 * <p>Always returns HTTP 200 with a minimal body; never throws to Feishu.</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/integrations/feishu")
@RequiredArgsConstructor
public class FeishuEventController {

    private final FeishuIntegrationMapper feishuIntegrationMapper;
    private final FeishuChatBindingMapper feishuChatBindingMapper;
    private final CryptoUtil cryptoUtil;
    private final ObjectMapper objectMapper;

    @PostMapping("/event")
    public Map<String, Object> event(@RequestBody(required = false) String rawBody) {
        try {
            JsonNode root = parse(rawBody);

            // Encrypted envelope: {"encrypt":"..."} — decrypt with an app's encrypt_key.
            if (root.hasNonNull("encrypt")) {
                JsonNode decrypted = tryDecryptWithAnyApp(root.get("encrypt").asText());
                if (decrypted != null) {
                    root = decrypted;
                }
            }

            // url_verification challenge handshake.
            if (root.hasNonNull("challenge") || "url_verification".equals(text(root, "type"))) {
                return Map.of("challenge", text(root, "challenge"));
            }

            handleEvent(root);
        } catch (Exception e) {
            log.warn("Feishu event handling failed: {}", e.getMessage());
        }
        // Always 200 so Feishu does not retry/disable the subscription.
        return Map.of("code", 0);
    }

    private void handleEvent(JsonNode root) {
        JsonNode header = root.path("header");
        String appId = header.path("app_id").asText(null);
        String eventType = header.path("event_type").asText(null);
        if (eventType == null) {
            // v1 fallback
            eventType = root.path("event").path("type").asText(null);
        }
        if (eventType == null) {
            return;
        }

        if ("im.chat.member.bot.added_v1".equals(eventType)) {
            JsonNode event = root.path("event");
            String chatId = event.path("chat_id").asText(null);
            if (chatId == null) {
                return;
            }
            FeishuIntegrationEntity integration = resolveByAppId(appId);
            if (integration == null) {
                log.warn("Bot-added event for unknown app_id; cannot auto-bind chat");
                return;
            }
            autoBindChat(integration, chatId);
        }
    }

    private void autoBindChat(FeishuIntegrationEntity integration, String chatId) {
        LambdaQueryWrapper<FeishuChatBindingEntity> w = new LambdaQueryWrapper<>();
        w.eq(FeishuChatBindingEntity::getFeishuIntegrationId, integration.getId())
                .eq(FeishuChatBindingEntity::getChatId, chatId)
                .last("LIMIT 1");
        if (feishuChatBindingMapper.selectOne(w) != null) {
            return; // already bound
        }
        FeishuChatBindingEntity binding = FeishuChatBindingEntity.builder()
                .feishuIntegrationId(integration.getId())
                .storeId(integration.getStoreId())
                .chatId(chatId)
                .chatType("group")
                .chatName("自动绑定群")
                .notifyOnApproval(true)
                .notifyOnExecution(true)
                .notifyOnRollback(true)
                .notifyOnRiskAlert(true)
                .status("active")
                .build();
        feishuChatBindingMapper.insert(binding);
        log.info("Auto-bound Feishu chat {} to integration {}", chatId, integration.getId());
    }

    private FeishuIntegrationEntity resolveByAppId(String appId) {
        if (appId == null || appId.isBlank()) {
            return null;
        }
        LambdaQueryWrapper<FeishuIntegrationEntity> w = new LambdaQueryWrapper<>();
        w.eq(FeishuIntegrationEntity::getAppId, appId)
                .eq(FeishuIntegrationEntity::getStatus, "active")
                .last("LIMIT 1");
        return feishuIntegrationMapper.selectOne(w);
    }

    /** Try every active app integration's encrypt_key until one decrypts cleanly. */
    private JsonNode tryDecryptWithAnyApp(String encrypt) {
        LambdaQueryWrapper<FeishuIntegrationEntity> w = new LambdaQueryWrapper<>();
        w.eq(FeishuIntegrationEntity::getConnectionType, "app")
                .isNotNull(FeishuIntegrationEntity::getEncryptKeyEncrypted);
        List<FeishuIntegrationEntity> apps = feishuIntegrationMapper.selectList(w);
        for (FeishuIntegrationEntity app : apps) {
            try {
                String key = cryptoUtil.decrypt(app.getEncryptKeyEncrypted());
                String json = decryptFeishuEvent(key, encrypt);
                JsonNode node = objectMapper.readTree(json);
                if (node != null && !node.isMissingNode()) {
                    return node;
                }
            } catch (Exception ignore) {
                // try the next app
            }
        }
        return null;
    }

    /**
     * Feishu event decryption: key = SHA-256(encryptKey); data = base64(encrypt);
     * iv = first 16 bytes; ciphertext = remainder; AES/CBC/PKCS5Padding.
     */
    private String decryptFeishuEvent(String encryptKey, String encrypt) throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        byte[] key = sha.digest(encryptKey.getBytes(StandardCharsets.UTF_8));
        byte[] data = java.util.Base64.getDecoder().decode(encrypt);
        byte[] iv = Arrays.copyOfRange(data, 0, 16);
        byte[] cipherText = Arrays.copyOfRange(data, 16, data.length);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
    }

    private JsonNode parse(String body) {
        try {
            return objectMapper.readTree(body == null ? "{}" : body);
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? "" : v.asText("");
    }
}
