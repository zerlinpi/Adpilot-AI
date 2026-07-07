package com.adpilot.modules.customer.service;

import com.adpilot.modules.customer.dto.TicketAiConfirmCommand;
import com.adpilot.modules.customer.vo.TicketAiProposalVo;

/**
 * AI assistance for Customer_Tickets surfaced under the 亚马逊 block
 * (platform-workspace-rbac Requirement 9).
 *
 * <p>The assistant drafts a reply, classifies the ticket, and suggests a
 * handling action, but always as <em>proposals</em> — nothing is sent or applied
 * until the operator explicitly confirms (Req 9.3, 9.4). Confirmation applies the
 * action and records the AI provenance in the audit trail (Req 9.5). Generation
 * failure leaves the ticket unchanged and surfaces the reason (Req 9.6).
 */
public interface TicketAiAssistantService {

    /**
     * Produce a draft reply, a suggested classification, and a suggested handling
     * action for the given ticket. This call never mutates the ticket and never
     * sends a reply (Req 9.3, 9.4).
     *
     * @param ticketId the ticket to assist
     * @return the AI proposals
     * @throws com.adpilot.common.exception.BusinessException when the ticket does
     *         not exist, is outside the operator's data scope, or AI generation
     *         fails (Req 9.6, 9.7)
     */
    TicketAiProposalVo assist(String ticketId);

    /**
     * Apply an operator-confirmed AI reply/action to the ticket and record that the
     * content originated from the Ticket_AI_Assistant in the audit trail
     * (Req 9.5).
     *
     * @param ticketId the ticket to update
     * @param command  the confirmed reply/classification/action
     * @return the updated ticket view
     */
    Object confirm(String ticketId, TicketAiConfirmCommand command);
}
