package com.adpilot.modules.automation.vo;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.Data;

/**
 * View of an automation rule template for the Automation Rules page (Req 25.1):
 * name (模板名称), type (模板类型), store (店铺), linked-object count
 * (关联对象数量), and status, plus the structured condition/action.
 */
@Data
@Builder
public class RuleTemplateVo {

    private String id;
    private String storeId;
    private String name;
    private String templateType;
    private JsonNode condition;
    private JsonNode action;
    private String status;
    private long linkedObjectCount;
    private String createdBy;
    private String createdAt;
    private String updatedAt;
}
