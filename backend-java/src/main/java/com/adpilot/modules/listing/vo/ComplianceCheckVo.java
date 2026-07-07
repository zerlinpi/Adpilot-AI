package com.adpilot.modules.listing.vo;
import lombok.Builder;
import lombok.Data;
import java.util.List;

@Data @Builder
public class ComplianceCheckVo {
    private List<ComplianceIssueVo> issues;
    private String overallRisk;
    private boolean canSubmit;

    @Data @Builder
    public static class ComplianceIssueVo {
        private String field;
        private String severity;
        private String rule;
        private String message;
        private String suggestion;
    }
}
