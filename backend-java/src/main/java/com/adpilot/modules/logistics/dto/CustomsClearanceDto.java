package com.adpilot.modules.logistics.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Request DTO for a {@code Customs_Clearance} (清关) update.
 * Bounds (Req 7.1): status one of not-started/declared/in-review/cleared/held,
 * declaration reference 1–100 chars, duties/taxes 0.00–999,999,999.99.
 */
@Data
public class CustomsClearanceDto {

    @NotBlank(message = "Clearance status is required")
    @Pattern(
            regexp = "not-started|declared|in-review|cleared|held",
            message = "Clearance status must be one of not-started, declared, in-review, cleared, held")
    private String clearanceStatus;

    @Size(min = 1, max = 100, message = "Declaration reference must be between 1 and 100 characters")
    private String declarationRef;

    @DecimalMin(value = "0.00", message = "Duties/taxes must be greater than or equal to 0.00")
    @DecimalMax(value = "999999999.99", message = "Duties/taxes must be less than or equal to 999,999,999.99")
    private BigDecimal dutiesTaxes;
}
