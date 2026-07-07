package com.adpilot.modules.customer.entity;

import com.baomidou.mybatisplus.annotation.TableName;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@TableName("customer_tickets")
@Table(name = "customer_tickets")
public class CustomerTicketEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(name = "store_id", nullable = false, columnDefinition = "char(36)")
    private UUID storeId;

    @Column(name = "order_id", length = 255)
    private String orderId;

    @Column(name = "buyer_email", length = 255)
    private String buyerEmail;

    @Column(name = "subject", length = 500)
    private String subject;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "category", length = 100)
    private String category;

    @Column(name = "priority", length = 20)
    @Builder.Default
    private String priority = "normal";

    @Column(name = "status", length = 30)
    @Builder.Default
    private String status = "open";

    @Column(name = "assigned_to", columnDefinition = "char(36)")
    private UUID assignedTo;

    /**
     * First-class Store_Group association inherited from the ticket's originating
     * Store (platform-workspace-rbac Req 9.2). Used by the shared data-scope layer
     * to keep tickets outside an operator's Store_Group_Scope invisible (Req 9.7).
     */
    @Column(name = "store_group_id", columnDefinition = "char(36)")
    private UUID storeGroupId;

    /**
     * AI provenance flag (Req 9.5): set when an operator confirms an
     * AI-originated reply or action for this ticket.
     */
    @Column(name = "ai_drafted")
    @Builder.Default
    private Boolean aiDrafted = false;

    /** Classification suggested by the Ticket_AI_Assistant and confirmed by the operator (Req 9.5). */
    @Column(name = "ai_classification", length = 100)
    private String aiClassification;

    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
