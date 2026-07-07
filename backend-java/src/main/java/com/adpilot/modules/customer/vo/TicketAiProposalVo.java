package com.adpilot.modules.customer.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The Ticket_AI_Assistant's output for a single Customer_Ticket
 * (platform-workspace-rbac Req 9.3).
 *
 * <p>Every field is a <em>proposal</em> only: the assistant never sends a reply
 * nor mutates the ticket. The values here take effect only after the operator
 * explicitly confirms them (Req 9.4), at which point provenance is recorded in
 * the audit trail (Req 9.5).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TicketAiProposalVo {

    /** The ticket these proposals were generated for. */
    private String ticketId;

    /** Proposed draft reply to the buyer. Not sent until confirmed. */
    private String draftReply;

    /** Proposed classification/category for the ticket. Not applied until confirmed. */
    private String suggestedClassification;

    /**
     * Proposed handling action, one of {@code reply}, {@code resolve},
     * {@code escalate}, or {@code pending}. Not applied until confirmed.
     */
    private String suggestedAction;

    /** Origin of the proposals: {@code ai} when model-generated, {@code template} otherwise. */
    private String generatedBy;
}
