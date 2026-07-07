package com.adpilot.modules.ai.service.impl;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.modules.ai.dto.AiSettingsRequest;
import com.adpilot.modules.ai.entity.AiSettings;
import com.adpilot.modules.ai.mapper.AiSettingsMapper;
import com.adpilot.modules.ai.service.AiSettingsService;
import com.adpilot.modules.ai.vo.AiSettingsVo;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiSettingsServiceImpl implements AiSettingsService {

    private final AiSettingsMapper aiSettingsMapper;

    @Override
    public AiSettings getActiveSettings() {
        LambdaQueryWrapper<AiSettings> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByAsc(AiSettings::getCreatedAt).last("LIMIT 1");
        return aiSettingsMapper.selectOne(wrapper);
    }

    @Override
    public AiSettingsVo getMaskedSettings() {
        AiSettings s = getActiveSettings();
        if (s == null) {
            return AiSettingsVo.builder()
                    .provider("openai")
                    .baseUrl("https://api.openai.com/v1")
                    .model("gpt-4o-mini")
                    .temperature(new BigDecimal("0.70"))
                    .maxTokens(1024)
                    .enabled(false)
                    .apiKeyConfigured(false)
                    .build();
        }
        return toVo(s);
    }

    @Override
    public AiSettingsVo saveSettings(AiSettingsRequest request) {
        if (request == null) {
            throw new BusinessException("AI_SETTINGS_REQUEST_REQUIRED", "AI settings request is required");
        }
        AiSettings existing = getActiveSettings();
        boolean isNew = existing == null;
        AiSettings s = existing != null ? existing : new AiSettings();
        if (isNew) {
            s.setCreatedAt(LocalDateTime.now());
        }

        if (request.getProvider() != null) s.setProvider(request.getProvider());
        if (request.getBaseUrl() != null) s.setBaseUrl(request.getBaseUrl());
        if (request.getModel() != null) s.setModel(request.getModel());
        if (request.getTemperature() != null) s.setTemperature(request.getTemperature());
        if (request.getMaxTokens() != null) s.setMaxTokens(request.getMaxTokens());
        if (request.getEnabled() != null) s.setEnabled(request.getEnabled());
        if (request.getExtraHeaders() != null) s.setExtraHeaders(request.getExtraHeaders());

        // Key handling: null = keep existing; "" = clear; otherwise set new.
        if (request.getApiKey() != null) {
            s.setApiKey(request.getApiKey().isBlank() ? null : request.getApiKey().trim());
        }

        // Sensible defaults
        if (s.getProvider() == null) s.setProvider("openai");
        if (s.getBaseUrl() == null) s.setBaseUrl("https://api.openai.com/v1");
        if (s.getModel() == null) s.setModel("gpt-4o-mini");
        if (s.getTemperature() == null) s.setTemperature(new BigDecimal("0.70"));
        if (s.getMaxTokens() == null) s.setMaxTokens(1024);
        if (s.getEnabled() == null) s.setEnabled(false);

        if (isNew) {
            aiSettingsMapper.insert(s);
        } else {
            aiSettingsMapper.updateById(s);
        }
        log.info("AI settings saved: provider={}, model={}, enabled={}", s.getProvider(), s.getModel(), s.getEnabled());
        return toVo(s);
    }

    private AiSettingsVo toVo(AiSettings s) {
        return AiSettingsVo.builder()
                .id(s.getId() != null ? s.getId().toString() : null)
                .provider(s.getProvider())
                .baseUrl(s.getBaseUrl())
                .apiKeyMasked(mask(s.getApiKey()))
                .apiKeyConfigured(s.getApiKey() != null && !s.getApiKey().isBlank())
                .model(s.getModel())
                .temperature(s.getTemperature())
                .maxTokens(s.getMaxTokens())
                .enabled(s.getEnabled())
                .extraHeaders(s.getExtraHeaders())
                .build();
    }

    private String mask(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        if (key.length() <= 8) {
            return "****";
        }
        return key.substring(0, 3) + "****" + key.substring(key.length() - 4);
    }
}
