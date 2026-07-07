package com.adpilot.modules.advertising.hosting;

import com.baomidou.mybatisplus.annotation.TableName;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Entity for the {@code hosting_kill_switches} table (Req 35.3).
 *
 * <p>Each row represents a kill switch at a given scope (system, organization, store,
 * or campaign). When {@code status} is "active", all new decision generation and submission
 * for the scoped entities is immediately halted.</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@TableName("hosting_kill_switches")
@Table(name = "hosting_kill_switches")
public class KillSwitchEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    /** Scope level: 'system', 'organization', 'store', or 'campaign'. */
    @Column(name = "scope", nullable = false, length = 20)
    private String scope;

    /** The ID of the scoped entity (null for system scope). */
    @Column(name = "scope_id", columnDefinition = "char(36)")
    private UUID scopeId;

    /** Store ID (null for system/org-level scopes that apply across stores). */
    @Column(name = "store_id", columnDefinition = "char(36)")
    private UUID storeId;

    /** Organization ID (null for system-level scope). */
    @Column(name = "org_id", columnDefinition = "char(36)")
    private UUID orgId;

    /** The user who activated the kill switch. */
    @Column(name = "activated_by", columnDefinition = "char(36)")
    private UUID activatedBy;

    /** When the kill switch was activated. */
    @Column(name = "activated_at")
    private LocalDateTime activatedAt;

    /** The user who deactivated the kill switch (null while still active). */
    @Column(name = "deactivated_by", columnDefinition = "char(36)")
    private UUID deactivatedBy;

    /** When the kill switch was deactivated (null if still active). */
    @Column(name = "deactivated_at")
    private LocalDateTime deactivatedAt;

    /** Human-readable reason for activation. */
    @Column(name = "reason", length = 500)
    private String reason;

    /** Status: 'active' or 'inactive'. */
    @Column(name = "status", nullable = false, length = 12)
    @Builder.Default
    private String status = "active";

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
