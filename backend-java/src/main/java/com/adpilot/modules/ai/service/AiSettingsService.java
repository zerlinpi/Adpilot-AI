package com.adpilot.modules.ai.service;

import com.adpilot.modules.ai.dto.AiSettingsRequest;
import com.adpilot.modules.ai.entity.AiSettings;
import com.adpilot.modules.ai.vo.AiSettingsVo;

public interface AiSettingsService {

    /** Active settings used by the AiClient (raw, with key). May be null if none. */
    AiSettings getActiveSettings();

    /** Masked settings for display in the configuration UI. */
    AiSettingsVo getMaskedSettings();

    /** Create or update the active settings; returns the masked view. */
    AiSettingsVo saveSettings(AiSettingsRequest request);
}
