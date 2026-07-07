package com.adpilot.modules.auth.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class UserInfo {

    private String id;
    private String email;
    private String name;
    private String avatarUrl;
    private String role;
    private List<String> permissions;
    private DepartmentInfo department;
    /** The user's configured default store, selected on next login (Req 5.2.3). */
    private String defaultStoreId;
}
