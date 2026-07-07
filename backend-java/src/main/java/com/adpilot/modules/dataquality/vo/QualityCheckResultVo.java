package com.adpilot.modules.dataquality.vo;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Data
@Builder
public class QualityCheckResultVo {

    private int checksPerformed;
    private int issuesFound;
    private Map<String, Integer> issuesByCategory;
    private String summary;
}
