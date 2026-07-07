package com.adpilot.modules.rbac.dto;

import com.adpilot.modules.rbac.PlatformFamily;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Command to create a Store_Group (platform-workspace-rbac Req 10.3). The name
 * must be 1-100 characters and unique within its platform family (Req 10.1,
 * 10.4); the platform family must be a Store_Group family ({@code amazon} or
 * {@code independent_site}), validated in the service layer.
 */
@Data
public class CreateStoreGroupCommand {

    @NotBlank(message = "Store group name is required")
    @Size(max = 100, message = "Store group name must not exceed 100 characters")
    private String name;

    @NotNull(message = "Platform family is required")
    private PlatformFamily platformFamily;
}
