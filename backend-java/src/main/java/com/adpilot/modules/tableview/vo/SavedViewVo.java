package com.adpilot.modules.tableview.vo;

import lombok.Builder;
import lombok.Data;

/**
 * Response VO for a {@code Saved_View}.
 */
@Data
@Builder
public class SavedViewVo {

    private String id;
    private String userId;
    private String tableKey;
    private String name;
    private String config;
    private String createdAt;
    private String updatedAt;
}
