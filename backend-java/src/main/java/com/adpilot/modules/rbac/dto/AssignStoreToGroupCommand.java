package com.adpilot.modules.rbac.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

/**
 * Command to (re)assign a Store to a Store_Group (platform-workspace-rbac
 * Req 10.5, 10.6, 11.4). The target group's platform family must match the
 * store's platform family, validated in the service layer.
 */
@Data
public class AssignStoreToGroupCommand {

    @NotNull(message = "Store id is required")
    private UUID storeId;

    @NotNull(message = "Store group id is required")
    private UUID storeGroupId;
}
