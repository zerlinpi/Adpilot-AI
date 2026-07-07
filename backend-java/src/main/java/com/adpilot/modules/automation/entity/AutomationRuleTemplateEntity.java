package com.adpilot.modules.automation.entity;

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
 * A reusable automation rule template (Req 25), backed by the
 * {@code automation_rule_templates} table (V6). A template pairs a condition
 * tree ({@code conditionJson}) with an action ({@code actionJson}); the
 * {@code RuleTemplateEvaluator} evaluates the condition against each linked
 * object's metrics on the scheduler cadence and applies the action when the
 * condition holds (Req 25.3).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@TableName("automation_rule_templates")
@Table(name = "automation_rule_templates")
public class AutomationRuleTemplateEntity {

    /** Active template status. */
    public static final String STATUS_ENABLED = "enabled";
    /** Disabled template status (not evaluated). */
    public static final String STATUS_DISABLED = "disabled";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "char(36)")
    private UUID id;

    @Column(name = "store_id", nullable = false, columnDefinition = "char(36)")
    private UUID storeId;

    @Column(nullable = false)
    private String name;

    @Column(name = "template_type", nullable = false, length = 50)
    private String templateType;

    @Column(name = "condition_json", nullable = false, columnDefinition = "json")
    private String conditionJson;

    @Column(name = "action_json", nullable = false, columnDefinition = "json")
    private String actionJson;

    @Column(length = 20)
    @Builder.Default
    private String status = STATUS_ENABLED;

    @Column(name = "created_by", columnDefinition = "char(36)")
    private UUID createdBy;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
