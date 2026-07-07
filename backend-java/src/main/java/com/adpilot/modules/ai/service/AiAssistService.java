package com.adpilot.modules.ai.service;

import com.adpilot.modules.ai.client.AiClient;
import com.adpilot.modules.ai.entity.AiSettings;
import com.adpilot.modules.audit.service.AiModelCallLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Convenience layer used by feature modules (Listing AI, Keyword Intelligence,
 * Review replies, recommendations, etc.). It:
 *  - checks whether AI is configured/enabled,
 *  - calls the model,
 *  - records the call in ai_model_call_logs,
 *  - and returns Optional.empty() on any failure so callers fall back to
 *    their existing template logic.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiAssistService {

    private final AiClient aiClient;
    private final AiSettingsService aiSettingsService;
    private final AiModelCallLogService aiModelCallLogService;

    public boolean isEnabled() {
        return aiClient.isEnabled();
    }

    /**
     * Generate text from a prompt, logging the call. Returns empty if AI is
     * disabled or the call fails, signalling the caller to use its fallback.
     */
    public Optional<String> generate(String feature, String userId, String storeId,
                                     String systemPrompt, String userPrompt) {
        if (!aiClient.isEnabled()) {
            return Optional.empty();
        }
        AiSettings settings = aiSettingsService.getActiveSettings();
        String model = settings != null ? settings.getModel() : "unknown";
        try {
            String output = aiClient.complete(systemPrompt, userPrompt);
            safeLog(userId, storeId, feature, model, systemPrompt, userPrompt, output, "success", null);
            return Optional.of(output);
        } catch (Exception e) {
            log.warn("AI assist '{}' failed, falling back: {}", feature, e.getMessage());
            safeLog(userId, storeId, feature, model, systemPrompt, userPrompt, null, "error", e.getMessage());
            return Optional.empty();
        }
    }

    private void safeLog(String userId, String storeId, String feature, String model,
                         String prompt, String input, String output, String status, String error) {
        try {
            aiModelCallLogService.createLog(userId, storeId, feature, model,
                    snippet(prompt), snippet(input), snippet(output), status, error);
        } catch (Exception e) {
            log.debug("Failed to record AI call log: {}", e.getMessage());
        }
    }

    private String snippet(String s) {
        if (s == null) return null;
        return s.length() > 2000 ? s.substring(0, 2000) : s;
    }
}
