package com.adpilot.modules.review.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ReviewResponseDto {

    @NotBlank(message = "Response text is required")
    private String responseText;
}
