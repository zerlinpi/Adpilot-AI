package com.adpilot.modules.logistics.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Request DTO for a {@code Carton_Spec} (箱规) entry.
 * Bounds (Req 5.1): box length/width/height cm 0.1–1000.0, box weight kg
 * 0.01–10000.00, units per box 1–1,000,000, box count 1–1,000,000.
 */
@Data
public class CartonSpecDto {

    @NotNull(message = "Box length is required")
    @DecimalMin(value = "0.1", message = "Box length (cm) must be greater than or equal to 0.1")
    @DecimalMax(value = "1000.0", message = "Box length (cm) must be less than or equal to 1000.0")
    private BigDecimal boxLengthCm;

    @NotNull(message = "Box width is required")
    @DecimalMin(value = "0.1", message = "Box width (cm) must be greater than or equal to 0.1")
    @DecimalMax(value = "1000.0", message = "Box width (cm) must be less than or equal to 1000.0")
    private BigDecimal boxWidthCm;

    @NotNull(message = "Box height is required")
    @DecimalMin(value = "0.1", message = "Box height (cm) must be greater than or equal to 0.1")
    @DecimalMax(value = "1000.0", message = "Box height (cm) must be less than or equal to 1000.0")
    private BigDecimal boxHeightCm;

    @NotNull(message = "Box weight is required")
    @DecimalMin(value = "0.01", message = "Box weight (kg) must be greater than or equal to 0.01")
    @DecimalMax(value = "10000.00", message = "Box weight (kg) must be less than or equal to 10000.00")
    private BigDecimal boxWeightKg;

    @NotNull(message = "Units per box is required")
    @Min(value = 1, message = "Units per box must be at least 1")
    @Max(value = 1_000_000, message = "Units per box must not exceed 1,000,000")
    private Integer unitsPerBox;

    @NotNull(message = "Box count is required")
    @Min(value = 1, message = "Box count must be at least 1")
    @Max(value = 1_000_000, message = "Box count must not exceed 1,000,000")
    private Integer boxCount;
}
