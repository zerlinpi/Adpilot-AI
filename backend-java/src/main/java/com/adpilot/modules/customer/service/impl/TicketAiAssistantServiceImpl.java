package com.adpilot.modules.customer.service.impl;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.common.security.AuditService;
import com.adpilot.modules.ai.service.AiAssistService;
import com.adpilot.modules.customer.dto.TicketAiConfirmCommand;
import com.adpilot.modules.customer.entity.CustomerTicketEntity;
import com.adpilot.modules.customer.mapper.CustomerTicketMapper;
import com.adpilot.modules.customer.service.TicketAiAssistantService;
import com.adpilot.modules.customer.vo.TicketAiProposalVo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Default {@link TicketAiAssistantService} implementation
 * (platform-workspace-rbac Requirement 9).
 *
 * <p>{@link #assist(String)} is a pure proposal step: it reads the ticket and
 * produces a draft reply, a suggested classification, and a suggested handling
 * action, but never persists anything and never sends a reply (Req 9.3, 9.4).
 * The ticket is mutated only by {@link #confirm(String, TicketAiConfirmCommand)},
 * the explicit operator-confirmation path, which also records the AI provenance
 * (Req 9.5).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TicketAiAssistantServiceImpl implements TicketAiAssistantService {

    private final CustomerTicketMapper customerTicketMapper;
    private final AiAssistService aiAssistService;
    private final AuditService auditService;

    @Override
    public TicketAiProposalVo assist(String ticketId) {
        CustomerTicketEntity ticket = customerTicketMapper.selectById(UUID.fromString(ticketId));
        if (ticket == null) {
            throw new BusinessException("TICKET_NOT_FOUND", "Customer ticket not found: " + ticketId);
        }

        String subject = ticket.getSubject() != null ? ticket.getSubject() : "(无主题)";
        String description = ticket.getDescription() != null ? ticket.getDescription() : "";

        String draft = null;
        String generatedBy = "template";
        if (aiAssistService.isEnabled()) {
            String system = "You are a professional Amazon seller customer-service agent. "
                    + "Write a concise, polite, empathetic reply to the buyer's ticket. "
                    + "Reply in the same language as the ticket. Output the reply text only.";
            String user = "Ticket subject: " + subject + "\n"
                    + "Ticket category: " + (ticket.getCategory() != null ? ticket.getCategory() : "general") + "\n"
                    + "Buyer message: " + description;
            Optional<String> out = aiAssistService.generate("ticket_ai_assist",
                    null, ticket.getStoreId() != null ? ticket.getStoreId().toString() : null, system, user);
            if (out.isPresent() && !out.get().isBlank()) {
                draft = out.get().trim();
                generatedBy = "ai";
            }
        }

        if (draft == null) {
            // Deterministic template fallback when AI is disabled/unavailable (Req 9.6 safe path).
            draft = "您好，\n\n感谢您的联系，我们已收到关于「" + subject + "」的反馈。"
                    + "非常抱歉给您带来不便，我们正在核实相关情况，并会尽快为您妥善处理。\n\n祝好，\n客服团队";
        }

        log.info("Generated AI proposals for ticket {} (by {}) - not applied until confirmed", ticketId, generatedBy);
        return TicketAiProposalVo.builder()
                .ticketId(ticketId)
                .draftReply(draft)
                .suggestedClassification(suggestClassification(ticket))
                .suggestedAction(suggestAction(ticket))
                .generatedBy(generatedBy)
                .build();
    }

    @Override
    @Transactional
    public Object confirm(String ticketId, TicketAiConfirmCommand command) {
        CustomerTicketEntity ticket = customerTicketMapper.selectById(UUID.fromString(ticketId));
        if (ticket == null) {
            throw new BusinessException("TICKET_NOT_FOUND", "Customer ticket not found: " + ticketId);
        }
        if (command == null) {
            throw new BusinessException("VALIDATION_ERROR", "Confirmation command is required");
        }

        boolean aiOriginated = false;
        if (command.getClassification() != null && !command.getClassification().isBlank()) {
            ticket.setCategory(command.getClassification().trim());
            ticket.setAiClassification(command.getClassification().trim());
            aiOriginated = true;
        }
        if (command.getAction() != null && !command.getAction().isBlank()) {
            ticket.setStatus(applyAction(command.getAction().trim(), ticket));
            aiOriginated = true;
        }
        if (command.getReply() != null && !command.getReply().isBlank()) {
            // A reply being confirmed advances the ticket out of the open state.
            if (ticket.getStatus() == null || "open".equalsIgnoreCase(ticket.getStatus())
                    || "pending".equalsIgnoreCase(ticket.getStatus())) {
                ticket.setStatus("in_progress");
            }
            aiOriginated = true;
        }

        if (aiOriginated) {
            // Record AI provenance on the ticket (Req 9.5).
            ticket.setAiDrafted(true);
        }
        ticket.setUpdatedAt(LocalDateTime.now());
        customerTicketMapper.updateById(ticket);

        // Record that the applied content originated from the Ticket_AI_Assistant (Req 9.5).
        auditService.recordPermit("ticket:ai_confirm:" + ticketId);
        log.info("Operator confirmed AI proposals for ticket {} (aiOriginated={})", ticketId, aiOriginated);

        return toTicketVo(ticket);
    }

    private String suggestClassification(CustomerTicketEntity ticket) {
        String category = ticket.getCategory();
        return (category != null && !category.isBlank()) ? category : "other";
    }

    private String suggestAction(CustomerTicketEntity ticket) {
        String status = ticket.getStatus() != null ? ticket.getStatus().toLowerCase() : "open";
        return switch (status) {
            case "resolved", "closed" -> "resolve";
            default -> "reply";
        };
    }

    private String applyAction(String action, CustomerTicketEntity ticket) {
        return switch (action.toLowerCase()) {
            case "resolve" -> "resolved";
            case "escalate" -> "in_progress";
            case "pending" -> "pending";
            case "reply" -> "in_progress";
            default -> ticket.getStatus() != null ? ticket.getStatus() : "pending";
        };
    }

    private Object toTicketVo(CustomerTicketEntity entity) {
        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("id", entity.getId() != null ? entity.getId().toString() : null);
        vo.put("storeId", entity.getStoreId() != null ? entity.getStoreId().toString() : null);
        vo.put("subject", entity.getSubject());
        vo.put("category", entity.getCategory());
        vo.put("status", entity.getStatus());
        vo.put("aiDrafted", entity.getAiDrafted());
        vo.put("aiClassification", entity.getAiClassification());
        return vo;
    }
}
