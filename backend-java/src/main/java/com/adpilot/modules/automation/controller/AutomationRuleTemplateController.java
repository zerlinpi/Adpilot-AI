package com.adpilot.modules.automation.controller;

import com.adpilot.common.api.ApiResponse;
import com.adpilot.common.security.RequirePermission;
import com.adpilot.modules.automation.dto.RuleTemplateBulkDto;
import com.adpilot.modules.automation.dto.RuleTemplateDto;
import com.adpilot.modules.automation.dto.RuleTemplateLinkDto;
import com.adpilot.modules.automation.service.RuleTemplateService;
import com.adpilot.modules.automation.vo.RuleTemplateBulkResultVo;
import com.adpilot.modules.automation.vo.RuleTemplateLinkVo;
import com.adpilot.modules.automation.vo.RuleTemplateVo;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Automation rule templates (Req 25) — reusable condition-to-action templates
 * applied to linked campaigns / targets / keywords by the
 * {@code RuleTemplateEvaluator}. All mutating endpoints require the existing
 * {@code automation:manage} permission (Req 25.6).
 */
@Slf4j
@RestController
@RequestMapping("/api/automation/rule-templates")
@RequiredArgsConstructor
public class AutomationRuleTemplateController {

    private final RuleTemplateService ruleTemplateService;

    /** GET /api/automation/rule-templates — list templates for a store (Req 25.1). */
    @GetMapping
    public ApiResponse<List<RuleTemplateVo>> listTemplates(
            @RequestParam(required = false) String storeId) {
        return ApiResponse.ok(ruleTemplateService.listTemplates(storeId));
    }

    /** POST /api/automation/rule-templates — create a template (Req 25.2, 25.6). */
    @PostMapping
    @RequirePermission("automation:manage")
    public ApiResponse<RuleTemplateVo> createTemplate(@Valid @RequestBody RuleTemplateDto dto) {
        return ApiResponse.ok(ruleTemplateService.createTemplate(dto));
    }

    /** PUT /api/automation/rule-templates/{id} — update a template (Req 25.2, 25.6). */
    @PutMapping("/{id}")
    @RequirePermission("automation:manage")
    public ApiResponse<RuleTemplateVo> updateTemplate(
            @PathVariable String id,
            @RequestBody RuleTemplateDto dto) {
        return ApiResponse.ok(ruleTemplateService.updateTemplate(id, dto));
    }

    /** POST /api/automation/rule-templates/bulk — bulk operation with per-item result (Req 25.4, 25.6). */
    @PostMapping("/bulk")
    @RequirePermission("automation:manage")
    public ApiResponse<List<RuleTemplateBulkResultVo>> bulkOperate(@Valid @RequestBody RuleTemplateBulkDto dto) {
        return ApiResponse.ok(ruleTemplateService.bulkOperate(dto));
    }

    /** POST /api/automation/rule-templates/{id}/links — link objects to a template (Req 25.3, 25.6). */
    @PostMapping("/{id}/links")
    @RequirePermission("automation:manage")
    public ApiResponse<RuleTemplateVo> linkObjects(
            @PathVariable String id,
            @Valid @RequestBody RuleTemplateLinkDto dto) {
        return ApiResponse.ok(ruleTemplateService.linkObjects(id, dto));
    }

    /** GET /api/automation/rule-templates/{id}/links — list the objects linked to a template (Req 25.3). */
    @GetMapping("/{id}/links")
    public ApiResponse<List<RuleTemplateLinkVo>> listLinks(@PathVariable String id) {
        return ApiResponse.ok(ruleTemplateService.listLinks(id));
    }

    /** DELETE /api/automation/rule-templates/{id}/links/{linkId} — unlink one object (Req 25.3, 25.6). */
    @DeleteMapping("/{id}/links/{linkId}")
    @RequirePermission("automation:manage")
    public ApiResponse<RuleTemplateVo> unlinkObject(
            @PathVariable String id,
            @PathVariable String linkId) {
        return ApiResponse.ok(ruleTemplateService.unlinkObject(id, linkId));
    }
}
