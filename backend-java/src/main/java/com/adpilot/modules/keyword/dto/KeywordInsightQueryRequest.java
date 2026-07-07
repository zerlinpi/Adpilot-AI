package com.adpilot.modules.keyword.dto;

import lombok.Data;

@Data
public class KeywordInsightQueryRequest {
    private String storeId;
    private String segment;
    private String status;
    private String source;
    private String riskLevel;
    private String search;
    private Integer page = 1;
    private Integer pageSize = 50;
}
