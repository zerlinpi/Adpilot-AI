package com.adpilot.modules.customer.service.impl;

import com.adpilot.common.security.AuditService;
import com.adpilot.modules.ai.service.AiAssistService;
import com.adpilot.modules.customer.dto.TicketAiConfirmCommand;
import com.adpilot.modules.customer.entity.CustomerTicketEntity;
import com.adpilot.modules.customer.mapper.CustomerTicketMapper;
import com.adpilot.modules.customer.vo.TicketAiProposalVo;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import org.mockito.Mockito;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Property-based test for the explicit-confirmation contract of the
 * {@link TicketAiAssistantServiceImpl}.
 *
 * <p>Feature: platform-workspace-rbac, Property 19: AI ticket outputs require
 * explicit confirmation. For any Ticket_AI_Assistant result, until the operator
 * explicitly confirms, no reply is sent and no ticket status or field is
 * mutated.
 *
 * <p>Validates: Requirements 9.4.
 *
 * <p>Requirement 9.4: "THE Ticket_AI_Assistant SHALL present its draft reply,
 * classification, and suggested action as proposals requiring explicit operator
 * confirmation before any reply is sent or status change is applied."
 *
 * <p>The properties model the persistence layer ({@link CustomerTicketMapper})
 * as a Mockito spy/mock so that any attempt to send a reply or mutate the ticket
 * surfaces as a write call ({@code updateById}/{@code insert}/{@code deleteById}).
 * The assist step must perform none of those, and the ticket entity it reads
 * must come back field-for-field unchanged. The complementary check shows that
 * the explicit {@code confirm} path is the one and only place a mutation occurs,
 * pinning down the "until the operator explicitly confirms" boundary.
 */
class TicketAiConfirmationPropertyTest {

    /**
     * Feature: platform-workspace-rbac, Property 19: AI ticket outputs require
     * explicit confirmation.
     *
     * <p>Validates: Requirements 9.4.
     *
     * <p>For any ticket and either AI availability branch, {@code assist} returns
     * proposals (a draft reply, a classification, and an action) yet never sends a
     * reply nor mutates any ticket field: no persistence write is issued and every
     * field of the read entity is left exactly as it was.
     */
    @Property(tries = 200)
    void assistProducesProposalsButNeverSendsAReplyOrMutatesTheTicket(
            @ForAll("tickets") CustomerTicketEntity ticket,
            @ForAll boolean aiEnabled,
            @ForAll("aiOutputs") String aiOutput) {

        CustomerTicketMapper ticketMapper = Mockito.mock(CustomerTicketMapper.class);
        AiAssistService aiAssistService = Mockito.mock(AiAssistService.class);
        AuditService auditService = Mockito.mock(AuditService.class);
        TicketAiAssistantServiceImpl service =
                new TicketAiAssistantServiceImpl(ticketMapper, aiAssistService, auditService);

        when(ticketMapper.selectById(ticket.getId())).thenReturn(ticket);
        when(aiAssistService.isEnabled()).thenReturn(aiEnabled);
        when(aiAssistService.generate(anyString(), any(), any(), anyString(), anyString()))
                .thenReturn(aiEnabled ? Optional.of(aiOutput) : Optional.empty());

        // Snapshot every mutable field before the assist call.
        String origSubject = ticket.getSubject();
        String origDescription = ticket.getDescription();
        String origCategory = ticket.getCategory();
        String origPriority = ticket.getPriority();
        String origStatus = ticket.getStatus();
        UUID origAssignedTo = ticket.getAssignedTo();
        Boolean origAiDrafted = ticket.getAiDrafted();
        String origAiClassification = ticket.getAiClassification();

        TicketAiProposalVo proposal = service.assist(ticket.getId().toString());

        // Proposals are produced for the operator to review.
        assertThat(proposal).isNotNull();
        assertThat(proposal.getTicketId()).isEqualTo(ticket.getId().toString());
        assertThat(proposal.getDraftReply()).isNotBlank();
        assertThat(proposal.getSuggestedClassification()).isNotBlank();
        assertThat(proposal.getSuggestedAction()).isNotBlank();

        // No reply is sent and no ticket field is mutated: zero persistence writes.
        verify(ticketMapper, never()).updateById(any());
        verify(ticketMapper, never()).insert(any());
        // No audit-trail provenance is recorded because nothing was applied (Req 9.5 is confirm-only).
        verify(auditService, never()).recordAuthorizationDecision(anyString(), org.mockito.ArgumentMatchers.anyBoolean());

        // The in-memory entity is field-for-field unchanged.
        assertThat(ticket.getSubject()).isEqualTo(origSubject);
        assertThat(ticket.getDescription()).isEqualTo(origDescription);
        assertThat(ticket.getCategory()).isEqualTo(origCategory);
        assertThat(ticket.getPriority()).isEqualTo(origPriority);
        assertThat(ticket.getStatus()).isEqualTo(origStatus);
        assertThat(ticket.getAssignedTo()).isEqualTo(origAssignedTo);
        assertThat(ticket.getAiDrafted()).isEqualTo(origAiDrafted);
        assertThat(ticket.getAiClassification()).isEqualTo(origAiClassification);
    }

    /**
     * Feature: platform-workspace-rbac, Property 19: AI ticket outputs require
     * explicit confirmation.
     *
     * <p>Validates: Requirements 9.4.
     *
     * <p>The boundary direction of the same property: an explicit operator
     * confirmation is the one path that applies the proposal. Confirming a
     * non-empty command issues exactly one persistence write and records the AI
     * provenance, demonstrating that mutation happens only on explicit
     * confirmation and not before it.
     */
    @Property(tries = 200)
    void explicitConfirmationIsTheOnlyPathThatAppliesTheProposal(
            @ForAll("tickets") CustomerTicketEntity ticket,
            @ForAll("confirmCommands") TicketAiConfirmCommand command) {

        CustomerTicketMapper ticketMapper = Mockito.mock(CustomerTicketMapper.class);
        AiAssistService aiAssistService = Mockito.mock(AiAssistService.class);
        AuditService auditService = Mockito.mock(AuditService.class);
        TicketAiAssistantServiceImpl service =
                new TicketAiAssistantServiceImpl(ticketMapper, aiAssistService, auditService);

        when(ticketMapper.selectById(ticket.getId())).thenReturn(ticket);

        service.confirm(ticket.getId().toString(), command);

        // Explicit confirmation is the mutation point: exactly one write is issued
        // and the AI provenance is recorded in the audit trail (Req 9.5).
        verify(ticketMapper).updateById(ticket);
        verify(auditService).recordPermit("ticket:ai_confirm:" + ticket.getId());
        assertThat(ticket.getAiDrafted()).isTrue();
    }

    // --- generators --------------------------------------------------------

    /** Tickets across the realistic category/priority/status space, each with a fresh id. */
    @Provide
    Arbitrary<CustomerTicketEntity> tickets() {
        Arbitrary<String> subjects = Arbitraries.strings().withCharRange('a', 'z')
                .ofMinLength(0).ofMaxLength(40);
        Arbitrary<String> descriptions = Arbitraries.strings().withCharRange('a', 'z')
                .ofMinLength(0).ofMaxLength(120);
        Arbitrary<String> categories = Arbitraries.of("other", "shipping", "refund", "product", "general");
        Arbitrary<String> priorities = Arbitraries.of("low", "medium", "high");
        Arbitrary<String> statuses = Arbitraries.of("open", "pending", "in_progress", "resolved", "closed");

        return Combinators.combine(subjects, descriptions, categories, priorities, statuses)
                .as((subject, description, category, priority, status) ->
                        CustomerTicketEntity.builder()
                                .id(UUID.randomUUID())
                                .storeId(UUID.randomUUID())
                                .subject(subject.isBlank() ? null : subject)
                                .description(description)
                                .category(category)
                                .priority(priority)
                                .status(status)
                                .aiDrafted(false)
                                .build());
    }

    /** AI model outputs: non-blank replies, mirroring the model's text response. */
    @Provide
    Arbitrary<String> aiOutputs() {
        return Arbitraries.strings().withCharRange('a', 'z').ofMinLength(1).ofMaxLength(80);
    }

    /**
     * Confirmation commands with at least one non-blank field, so a confirmation
     * always carries something to apply.
     */
    @Provide
    Arbitrary<TicketAiConfirmCommand> confirmCommands() {
        Arbitrary<String> replies = Arbitraries.strings().withCharRange('a', 'z')
                .ofMinLength(1).ofMaxLength(60);
        Arbitrary<String> classifications = Arbitraries.of("other", "shipping", "refund", "product");
        Arbitrary<String> actions = Arbitraries.of("reply", "resolve", "escalate", "pending");

        return Combinators.combine(replies, classifications, actions)
                .as((reply, classification, action) -> TicketAiConfirmCommand.builder()
                        .reply(reply)
                        .classification(classification)
                        .action(action)
                        .build());
    }
}
