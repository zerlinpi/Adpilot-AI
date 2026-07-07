package com.adpilot.modules.advertising.operation;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * The structured AI-decision payload recorded on an {@code Operation_Record} when the AI optimizer
 * makes a decision, serialized into the {@code operations.ai_decision} JSON column.
 *
 * <p>Per Requirement 49.9, when the AI optimizer makes a decision the Advertising_Module records on
 * the Operation_Record: the trigger metric, the resolved AI_Personality used, the
 * Personality_Rule_Version, the personality-allowed maximum magnitude, the actual applied magnitude,
 * the decision reason, the before value, the after value, the predicted impact, whether approval is
 * required, and the Amazon sync result. The before/after values and the Personality_Rule_Version are
 * first-class columns on {@link OperationEntity} ({@code before_value}, {@code after_value},
 * {@code personality_rule_version}); this object carries the remaining decision-specific fields that
 * have no dedicated column and are persisted together as JSON. The same payload supports the
 * hosting-rollback audit trail of Requirement 22.10.</p>
 *
 * <p>{@code null} fields are omitted from the serialized JSON so a partially-populated decision does
 * not write spurious {@code null}s into the audit record.</p>
 *
 * <p>Validates: Requirements 49.9, 22.10.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AiDecision {

    /** The metric that triggered the decision (for example {@code acos}, {@code conversion_rate}). */
    private String triggerMetric;

    /** The numeric value of the trigger metric at decision time, where applicable. */
    private BigDecimal triggerValue;

    /** The resolved AI_Personality machine value used ({@code conservative}/{@code balanced}/{@code aggressive}). */
    private String resolvedPersonality;

    /** The personality-allowed MAXIMUM change magnitude (ratio) for this adjustment. */
    private BigDecimal personalityAllowedMagnitude;

    /** The change magnitude (ratio) the optimizer ACTUALLY applied after Safety_Boundary clamping. */
    private BigDecimal appliedMagnitude;

    /** The human-readable reason the optimizer chose this adjustment. */
    private String decisionReason;

    /** The predicted impact of the adjustment (free-form, for example a projected ACoS/sales delta). */
    private String predictedImpact;

    /** Whether the adjustment requires operator approval before submission (Req 49.19). */
    private Boolean approvalRequired;

    /** The Amazon sync result for the adjustment, where Operation_Write_Back provided one. */
    private String syncResult;
}
