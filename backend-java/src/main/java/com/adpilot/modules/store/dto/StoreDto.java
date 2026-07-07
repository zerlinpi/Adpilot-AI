package com.adpilot.modules.store.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class StoreDto {

    @NotBlank(message = "Store name is required")
    private String name;

    @NotBlank(message = "Marketplace ID is required")
    private String marketplaceId;

    private String sellerId;

    /** Optional connection status: connected | disconnected | error. */
    private String status;
}
