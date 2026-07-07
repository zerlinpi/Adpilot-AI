package com.adpilot.modules.automation.service;

import com.adpilot.modules.automation.dto.RuleTemplateBulkDto;
import com.adpilot.modules.automation.dto.RuleTemplateDto;
import com.adpilot.modules.automation.dto.RuleTemplateLinkDto;
import com.adpilot.modules.automation.vo.RuleTemplateBulkResultVo;
import com.adpilot.modules.automation.vo.RuleTemplateLinkVo;
import com.adpilot.modules.automation.vo.RuleTemplateVo;

import java.util.List;

/**
 * CRUD + linking + bulk operations for automation rule templates (Req 25).
 * Condition/action payloads are validated against the pure
 * {@link com.adpilot.modules.automation.support.RuleConditionEvaluator}
 * grammar on write.
 */
public interface RuleTemplateService {

    /** List the rule templates for a store (Req 25.1), with linked-object counts. */
    List<RuleTemplateVo> listTemplates(String storeId);

    /** Create a rule template and return the created record (Req 25.2). */
    RuleTemplateVo createTemplate(RuleTemplateDto dto);

    /** Update an existing rule template (Req 25.2). */
    RuleTemplateVo updateTemplate(String id, RuleTemplateDto dto);

    /** Apply a bulk operation, returning a per-item result (Req 25.4). */
    List<RuleTemplateBulkResultVo> bulkOperate(RuleTemplateBulkDto dto);

    /** Link one or more objects (campaign/target/keyword/product) to a template (Req 25.3). */
    RuleTemplateVo linkObjects(String id, RuleTemplateLinkDto dto);

    /** List the objects linked to a template, with resolved display names (Req 25.3). */
    List<RuleTemplateLinkVo> listLinks(String id);

    /** Remove a single linked object from a template (Req 25.3). */
    RuleTemplateVo unlinkObject(String templateId, String linkId);
}
