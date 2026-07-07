package com.adpilot.modules.ai.controller;

import com.adpilot.common.api.ApiResponse;
import com.adpilot.common.security.RequirePermission;
import com.adpilot.modules.ai.client.AiClient;
import com.adpilot.modules.ai.dto.AiSettingsRequest;
import com.adpilot.modules.ai.service.AiSettingsService;
import com.adpilot.modules.ai.vo.AiSettingsVo;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * AI configuration endpoints. Lets operators point the system at any
 * OpenAI-compatible provider at runtime.
 */
@RestController
@RequestMapping("/api/settings/ai")
@RequiredArgsConstructor
public class AiSettingsController {

    private final AiSettingsService aiSettingsService;
    private final AiClient aiClient;

    @GetMapping
    @RequirePermission("hosting:manage")
    public ApiResponse<AiSettingsVo> get() {
        return ApiResponse.ok(aiSettingsService.getMaskedSettings());
    }

    @PutMapping
    @RequirePermission("hosting:manage")
    public ApiResponse<AiSettingsVo> update(@RequestBody AiSettingsRequest request) {
        return ApiResponse.ok(aiSettingsService.saveSettings(request));
    }

    @PostMapping("/test")
    @RequirePermission("hosting:manage")
    public ApiResponse<Map<String, String>> test() {
        if (!aiClient.isEnabled()) {
            return ApiResponse.ok(Map.of("result", "AI is not enabled or not fully configured."));
        }
        try {
            String reply = aiClient.complete(
                    "You are a connectivity test. Reply with the single word: OK.",
                    "ping");
            return ApiResponse.ok(Map.of("result", "OK - model responded: " + reply));
        } catch (Exception e) {
            return ApiResponse.ok(Map.of("result", "FAILED - " + e.getMessage()));
        }
    }
}
