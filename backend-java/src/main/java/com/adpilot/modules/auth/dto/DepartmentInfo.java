package com.adpilot.modules.auth.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class DepartmentInfo {

    private String id;
    private String name;
    private String code;
}
