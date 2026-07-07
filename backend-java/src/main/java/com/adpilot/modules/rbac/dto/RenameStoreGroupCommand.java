package com.adpilot.modules.rbac.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Command to rename a Store_Group (platform-workspace-rbac Req 10.4). The new
 * name must be 1-100 characters and remain unique within the group's platform
 * family; uniqueness is enforced in the service layer.
 */
@Data
public class RenameStoreGroupCommand {

    @NotBlank(message = "Store group name is required")
    @Size(max = 100, message = "Store group name must not exceed 100 characters")
    private String name;
}
