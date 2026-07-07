package com.adpilot.modules.advertising.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Payload for {@code POST /api/smart-diagnosis/tasks} — create a Smart
 * Diagnosis task for a parent ASIN (Req 22.1). {@code storeId} and
 * {@code parentAsin} are required; {@code updateFrequency} defaults to
 * {@code manual} when omitted.
 */
@Data
public class SmartDiagnosisCreateRequest {

    @NotBlank(message = "Store ID is required")
    private String storeId;

    @NotBlank(message = "Parent ASIN is required")
    @Size(max = 20, message = "Parent ASIN must be at most 20 characters")
    private String parentAsin;

    /** Update frequency (更新频率): {@code manual} | {@code daily} | {@code weekly}; defaults to {@code manual}. */
    private String updateFrequency;
}
