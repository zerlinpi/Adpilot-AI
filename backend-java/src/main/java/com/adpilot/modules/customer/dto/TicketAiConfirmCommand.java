package com.adpilot.modules.customer.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Operator confirmation of an AI-proposed ticket reply/action
 * (platform-workspace-rbac Req 9.4, 9.5).
 *
 * <p>Only the fields the operator confirms are applied; a {@code null} field is
 * left unchanged on the ticket. Confirming records that the applied content
 * originated from the Ticket_AI_Assistant in the audit trail.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TicketAiConfirmCommand {

    /** The (possibly operator-edited) reply text to send. */
    private String reply;

    /** The classification/category to apply to the ticket. */
    private String classification;

    /**
     * The handling action to apply: {@code reply}, {@code resolve},
     * {@code escalate}, or {@code pending}.
     */
    private String action;
}
