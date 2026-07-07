package com.adpilot.modules.automation.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Links an {@link AutomationRuleTemplateEntity} to a single governed object — a
 * campaign, targeting object, or keyword (Req 25.3) — backed by the
 * {@code automation_rule_template_links} table (V6). A given object is linked
 * to a template at most once (enforced by {@code uk_template_object}).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@TableName("automation_rule_template_links")
@Table(name = "automation_rule_template_links")
public class AutomationRuleTemplateLinkEntity {

    /** Linked-object types. */
    public static final String OBJECT_CAMPAIGN = "campaign";
    public static final String OBJECT_TARGET = "target";
    public static final String OBJECT_KEYWORD = "keyword";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(name = "template_id", nullable = false, columnDefinition = "char(36)")
    private UUID templateId;

    @Column(name = "object_type", nullable = false, length = 30)
    private String objectType;

    @Column(name = "object_id", nullable = false, columnDefinition = "char(36)")
    private UUID objectId;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
