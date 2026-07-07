package com.adpilot.modules.advertising.dto;

import lombok.Data;

@Data
public class GoalQueryRequest {

    private String storeId;
    private String status;
    private String type;
    private int page = 1;
    private int pageSize = 50;
}
