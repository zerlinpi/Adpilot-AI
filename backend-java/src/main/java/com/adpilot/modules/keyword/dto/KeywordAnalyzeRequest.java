package com.adpilot.modules.keyword.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class KeywordAnalyzeRequest {
    @NotBlank(message = "storeId is required")
    private String storeId;
}
