package com.adpilot.modules.dashboard.vo;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * AI Actions panel (AI动作) of the AI advertising dashboard (Req 18.3). Lists
 * the standard SP AI_Action set, each with its execution count and measured
 * impact over the selected period, derived from {@code automation_executions}.
 */
@Data
@Builder
public class AiActionsVo {

    /** Reporting currency for any monetary impact amounts. */
    private String currency;

    /** The standard AI_Action set; entries with no activity report a count of 0. */
    @Builder.Default
    private List<AiActionVo> actions = new ArrayList<>();
}
