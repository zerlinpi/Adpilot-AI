package com.adpilot.modules.automation.service.impl;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.advertising.entity.CampaignEntity;
import com.adpilot.modules.advertising.entity.GoalEntity;
import com.adpilot.modules.advertising.entity.KeywordEntity;
import com.adpilot.modules.advertising.entity.TargetEntity;
import com.adpilot.modules.advertising.mapper.CampaignMapper;
import com.adpilot.modules.advertising.mapper.GoalMapper;
import com.adpilot.modules.advertising.mapper.KeywordMapper;
import com.adpilot.modules.advertising.mapper.TargetMapper;
import com.adpilot.modules.audit.service.AuditLogService;
import com.adpilot.modules.automation.dto.RuleTemplateBulkDto;
import com.adpilot.modules.automation.dto.RuleTemplateDto;
import com.adpilot.modules.automation.dto.RuleTemplateLinkDto;
import com.adpilot.modules.automation.entity.AutomationRuleTemplateEntity;
import com.adpilot.modules.automation.entity.AutomationRuleTemplateLinkEntity;
import com.adpilot.modules.automation.mapper.AutomationRuleTemplateLinkMapper;
import com.adpilot.modules.automation.mapper.AutomationRuleTemplateMapper;
import com.adpilot.modules.automation.service.RuleTemplateService;
import com.adpilot.modules.automation.support.RuleConditionCodec;
import com.adpilot.modules.automation.vo.RuleTemplateBulkResultVo;
import com.adpilot.modules.automation.vo.RuleTemplateLinkVo;
import com.adpilot.modules.automation.vo.RuleTemplateVo;
import com.adpilot.modules.product.entity.ProductEntity;
import com.adpilot.modules.product.mapper.ProductMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Default {@link RuleTemplateService}. Condition/action JSON is validated
 * against the pure {@link RuleConditionCodec} grammar on every write so a stored
 * template is always evaluable by the {@code RuleTemplateEvaluator} (Req 25.3,
 * 25.5).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RuleTemplateServiceImpl implements RuleTemplateService {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Set<String> VALID_OBJECT_TYPES = Set.of(
            AutomationRuleTemplateLinkEntity.OBJECT_CAMPAIGN,
            AutomationRuleTemplateLinkEntity.OBJECT_TARGET,
            AutomationRuleTemplateLinkEntity.OBJECT_KEYWORD);
    /** Pseudo object type accepted on input only: resolved to campaign links (Req 25.3). */
    private static final String OBJECT_PRODUCT = "product";

    private final AutomationRuleTemplateMapper templateMapper;
    private final AutomationRuleTemplateLinkMapper linkMapper;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;
    private final CampaignMapper campaignMapper;
    private final KeywordMapper keywordMapper;
    private final TargetMapper targetMapper;
    private final GoalMapper goalMapper;
    private final ProductMapper productMapper;

    @Override
    public List<RuleTemplateVo> listTemplates(String storeId) {
        LambdaQueryWrapper<AutomationRuleTemplateEntity> wrapper = new LambdaQueryWrapper<>();
        if (storeId != null && !storeId.isBlank()) {
            wrapper.eq(AutomationRuleTemplateEntity::getStoreId, UUID.fromString(storeId));
        }
        wrapper.orderByDesc(AutomationRuleTemplateEntity::getCreatedAt);
        return templateMapper.selectList(wrapper).stream()
                .map(this::toVo)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public RuleTemplateVo createTemplate(RuleTemplateDto dto) {
        UUID storeId = parseUuid(dto.getStoreId(), "storeId");
        String conditionJson = validateAndSerializeCondition(dto.getCondition());
        String actionJson = validateAndSerializeAction(dto.getAction());

        AutomationRuleTemplateEntity entity = AutomationRuleTemplateEntity.builder()
                .storeId(storeId)
                .name(dto.getName())
                .templateType(dto.getTemplateType())
                .conditionJson(conditionJson)
                .actionJson(actionJson)
                .status(normalizeStatus(dto.getStatus()))
                .createdBy(currentUserId())
                .build();
        templateMapper.insert(entity);
        log.info("自动化规则模板已创建: id={}, storeId={}", entity.getId(), storeId);

        audit("CREATE", entity.getId(), dto);
        return toVo(entity);
    }

    @Override
    @Transactional
    public RuleTemplateVo updateTemplate(String id, RuleTemplateDto dto) {
        AutomationRuleTemplateEntity entity = requireTemplate(id);

        if (dto.getName() != null && !dto.getName().isBlank()) {
            entity.setName(dto.getName());
        }
        if (dto.getTemplateType() != null && !dto.getTemplateType().isBlank()) {
            entity.setTemplateType(dto.getTemplateType());
        }
        if (dto.getCondition() != null && !dto.getCondition().isNull()) {
            entity.setConditionJson(validateAndSerializeCondition(dto.getCondition()));
        }
        if (dto.getAction() != null && !dto.getAction().isNull()) {
            entity.setActionJson(validateAndSerializeAction(dto.getAction()));
        }
        if (dto.getStatus() != null && !dto.getStatus().isBlank()) {
            entity.setStatus(normalizeStatus(dto.getStatus()));
        }
        entity.setUpdatedAt(LocalDateTime.now());
        templateMapper.updateById(entity);
        log.info("自动化规则模板已更新: id={}", entity.getId());

        audit("UPDATE", entity.getId(), dto);
        return toVo(entity);
    }

    @Override
    @Transactional
    public List<RuleTemplateBulkResultVo> bulkOperate(RuleTemplateBulkDto dto) {
        String operation = dto.getOperation() == null ? "" : dto.getOperation().trim().toLowerCase();
        if (!Set.of("enable", "disable", "delete").contains(operation)) {
            throw new BusinessException("INVALID_OPERATION", "不支持的批量操作: " + dto.getOperation());
        }

        List<RuleTemplateBulkResultVo> results = new ArrayList<>();
        for (String rawId : dto.getIds()) {
            try {
                UUID templateId = UUID.fromString(rawId);
                AutomationRuleTemplateEntity entity = templateMapper.selectById(templateId);
                if (entity == null) {
                    results.add(RuleTemplateBulkResultVo.builder()
                            .id(rawId).success(false).message("模板不存在").build());
                    continue;
                }
                switch (operation) {
                    case "enable":
                        entity.setStatus(AutomationRuleTemplateEntity.STATUS_ENABLED);
                        templateMapper.updateById(entity);
                        break;
                    case "disable":
                        entity.setStatus(AutomationRuleTemplateEntity.STATUS_DISABLED);
                        templateMapper.updateById(entity);
                        break;
                    case "delete":
                        deleteLinks(templateId);
                        templateMapper.deleteById(templateId);
                        break;
                    default:
                        // unreachable
                }
                audit(operation.toUpperCase(), templateId, dto);
                results.add(RuleTemplateBulkResultVo.builder()
                        .id(rawId).success(true).message("成功").build());
            } catch (Exception ex) {
                // Per-item isolation: one failure does not abort the rest.
                log.warn("批量操作失败: id={}, operation={}", rawId, operation, ex);
                results.add(RuleTemplateBulkResultVo.builder()
                        .id(rawId).success(false).message(ex.getMessage()).build());
            }
        }
        return results;
    }

    @Override
    @Transactional
    public RuleTemplateVo linkObjects(String id, RuleTemplateLinkDto dto) {
        AutomationRuleTemplateEntity template = requireTemplate(id);
        UUID templateId = template.getId();

        for (RuleTemplateLinkDto.Link link : dto.getLinks()) {
            String objectType = link.getObjectType() == null ? "" : link.getObjectType().trim().toLowerCase();
            UUID objectId = parseUuid(link.getObjectId(), "objectId");

            // A "product" link is not stored directly; it resolves to the
            // campaigns that promote the product and creates a campaign link for
            // each, so the evaluator (which iterates every linked object) acts on
            // all of the product's campaigns (Req 25.3).
            if (OBJECT_PRODUCT.equals(objectType)) {
                for (UUID campaignId : resolveProductCampaigns(objectId)) {
                    insertLinkIfAbsent(templateId, AutomationRuleTemplateLinkEntity.OBJECT_CAMPAIGN, campaignId);
                }
                continue;
            }

            if (!VALID_OBJECT_TYPES.contains(objectType)) {
                throw new BusinessException("INVALID_OBJECT_TYPE",
                        "无效的关联对象类型: " + link.getObjectType());
            }
            insertLinkIfAbsent(templateId, objectType, objectId);
        }
        audit("LINK", templateId, dto);
        return toVo(template);
    }

    @Override
    public List<RuleTemplateLinkVo> listLinks(String id) {
        AutomationRuleTemplateEntity template = requireTemplate(id);
        List<AutomationRuleTemplateLinkEntity> links = linkMapper.selectList(
                new LambdaQueryWrapper<AutomationRuleTemplateLinkEntity>()
                        .eq(AutomationRuleTemplateLinkEntity::getTemplateId, template.getId())
                        .orderByDesc(AutomationRuleTemplateLinkEntity::getCreatedAt));
        return links.stream().map(this::toLinkVo).collect(Collectors.toList());
    }

    @Override
    @Transactional
    public RuleTemplateVo unlinkObject(String templateId, String linkId) {
        AutomationRuleTemplateEntity template = requireTemplate(templateId);
        UUID linkUuid = parseUuid(linkId, "linkId");
        AutomationRuleTemplateLinkEntity link = linkMapper.selectById(linkUuid);
        if (link == null || !template.getId().equals(link.getTemplateId())) {
            throw new BusinessException("LINK_NOT_FOUND", "关联对象不存在: " + linkId);
        }
        linkMapper.deleteById(linkUuid);
        audit("UNLINK", template.getId(), linkId);
        return toVo(template);
    }

    // ----- linking helpers -----

    /** Insert a link, skipping when the object is already linked (idempotent). */
    private void insertLinkIfAbsent(UUID templateId, String objectType, UUID objectId) {
        LambdaQueryWrapper<AutomationRuleTemplateLinkEntity> exists = new LambdaQueryWrapper<>();
        exists.eq(AutomationRuleTemplateLinkEntity::getTemplateId, templateId)
                .eq(AutomationRuleTemplateLinkEntity::getObjectType, objectType)
                .eq(AutomationRuleTemplateLinkEntity::getObjectId, objectId);
        if (linkMapper.selectCount(exists) > 0) {
            return;
        }
        linkMapper.insert(AutomationRuleTemplateLinkEntity.builder()
                .templateId(templateId)
                .objectType(objectType)
                .objectId(objectId)
                .build());
    }

    /**
     * Resolve a product to the campaigns that promote it. The designed path is
     * {@code product -> goals.product_ids (JSON) -> campaigns.goal_id}: a goal
     * declares the products it optimizes, and each campaign belongs to a goal.
     * Returns an empty set when the product (or its campaigns) cannot be found,
     * so a product with no campaigns simply links nothing.
     */
    private Set<UUID> resolveProductCampaigns(UUID productId) {
        ProductEntity product = productMapper.selectById(productId);
        if (product == null) {
            throw new BusinessException("PRODUCT_NOT_FOUND", "产品不存在: " + productId);
        }
        String productKey = productId.toString();

        List<GoalEntity> goals = goalMapper.selectList(
                new LambdaQueryWrapper<GoalEntity>()
                        .eq(GoalEntity::getStoreId, product.getStoreId()));
        Set<UUID> goalIds = new LinkedHashSet<>();
        for (GoalEntity goal : goals) {
            if (jsonArrayContains(goal.getProductIds(), productKey)) {
                goalIds.add(goal.getId());
            }
        }
        if (goalIds.isEmpty()) {
            return Set.of();
        }
        List<CampaignEntity> campaigns = campaignMapper.selectList(
                new LambdaQueryWrapper<CampaignEntity>()
                        .eq(CampaignEntity::getStoreId, product.getStoreId())
                        .in(CampaignEntity::getGoalId, goalIds));
        return campaigns.stream()
                .map(CampaignEntity::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** True when {@code json} is a JSON array containing the given string value. */
    private boolean jsonArrayContains(String json, String value) {
        if (json == null || json.isBlank()) {
            return false;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node == null || !node.isArray()) {
                return false;
            }
            for (JsonNode element : node) {
                if (value.equals(element.asText())) {
                    return true;
                }
            }
        } catch (Exception ex) {
            log.debug("解析 product_ids JSON 失败(已忽略): {}", ex.getMessage());
        }
        return false;
    }

    private RuleTemplateLinkVo toLinkVo(AutomationRuleTemplateLinkEntity link) {
        return RuleTemplateLinkVo.builder()
                .id(link.getId() != null ? link.getId().toString() : null)
                .objectType(link.getObjectType())
                .objectId(link.getObjectId() != null ? link.getObjectId().toString() : null)
                .objectName(resolveObjectName(link.getObjectType(), link.getObjectId()))
                .createdAt(link.getCreatedAt() != null ? link.getCreatedAt().format(FORMATTER) : null)
                .build();
    }

    /** Best-effort display name for a linked object; falls back to the id text. */
    private String resolveObjectName(String objectType, UUID objectId) {
        if (objectType == null || objectId == null) {
            return objectId != null ? objectId.toString() : null;
        }
        try {
            switch (objectType.toLowerCase()) {
                case AutomationRuleTemplateLinkEntity.OBJECT_CAMPAIGN: {
                    CampaignEntity c = campaignMapper.selectById(objectId);
                    return c != null ? c.getName() : objectId.toString();
                }
                case AutomationRuleTemplateLinkEntity.OBJECT_KEYWORD: {
                    KeywordEntity k = keywordMapper.selectById(objectId);
                    return k != null ? k.getKeywordText() : objectId.toString();
                }
                case AutomationRuleTemplateLinkEntity.OBJECT_TARGET: {
                    TargetEntity t = targetMapper.selectById(objectId);
                    return t != null ? t.getTargetingValue() : objectId.toString();
                }
                default:
                    return objectId.toString();
            }
        } catch (Exception ex) {
            return objectId.toString();
        }
    }

    // ----- helpers -----

    private AutomationRuleTemplateEntity requireTemplate(String id) {
        AutomationRuleTemplateEntity entity = templateMapper.selectById(parseUuid(id, "id"));
        if (entity == null) {
            throw new BusinessException("TEMPLATE_NOT_FOUND", "规则模板不存在: " + id);
        }
        return entity;
    }

    private void deleteLinks(UUID templateId) {
        LambdaQueryWrapper<AutomationRuleTemplateLinkEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AutomationRuleTemplateLinkEntity::getTemplateId, templateId);
        linkMapper.delete(wrapper);
    }

    private long countLinks(UUID templateId) {
        LambdaQueryWrapper<AutomationRuleTemplateLinkEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AutomationRuleTemplateLinkEntity::getTemplateId, templateId);
        return linkMapper.selectCount(wrapper);
    }

    /** Validate the condition JSON parses into the evaluator grammar, then store its text. */
    private String validateAndSerializeCondition(JsonNode condition) {
        if (condition == null || condition.isNull()) {
            throw new BusinessException("INVALID_CONDITION", "condition 不能为空");
        }
        String json = condition.toString();
        try {
            RuleConditionCodec.parseCondition(json);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("INVALID_CONDITION", "条件无效: " + ex.getMessage());
        }
        return json;
    }

    /** Validate the action JSON parses into the evaluator grammar, then store its text. */
    private String validateAndSerializeAction(JsonNode action) {
        if (action == null || action.isNull()) {
            throw new BusinessException("INVALID_ACTION", "action 不能为空");
        }
        String json = action.toString();
        try {
            RuleConditionCodec.parseAction(json);
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("INVALID_ACTION", "动作无效: " + ex.getMessage());
        }
        return json;
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return AutomationRuleTemplateEntity.STATUS_ENABLED;
        }
        String s = status.trim().toLowerCase();
        if (!AutomationRuleTemplateEntity.STATUS_ENABLED.equals(s)
                && !AutomationRuleTemplateEntity.STATUS_DISABLED.equals(s)) {
            throw new BusinessException("INVALID_STATUS", "无效的状态: " + status);
        }
        return s;
    }

    private UUID parseUuid(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BusinessException("INVALID_ID", field + " 不能为空");
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException ex) {
            throw new BusinessException("INVALID_ID", field + " 不是有效的 UUID: " + value);
        }
    }

    private UUID currentUserId() {
        String id = SecurityUtils.getCurrentUserIdOrNull();
        return id == null ? null : UUID.fromString(id);
    }

    private void audit(String action, UUID entityId, Object details) {
        try {
            UUID userId = currentUserId();
            UUID orgId = SecurityUtils.isAuthenticated() && SecurityUtils.getCurrentOrgId() != null
                    ? UUID.fromString(SecurityUtils.getCurrentOrgId()) : null;
            auditLogService.createLog(userId, orgId, action, "automation_rule_template", entityId, details);
        } catch (Exception ex) {
            log.debug("审计日志写入失败(已忽略): {}", ex.getMessage());
        }
    }

    private RuleTemplateVo toVo(AutomationRuleTemplateEntity entity) {
        return RuleTemplateVo.builder()
                .id(entity.getId().toString())
                .storeId(entity.getStoreId() != null ? entity.getStoreId().toString() : null)
                .name(entity.getName())
                .templateType(entity.getTemplateType())
                .condition(readTree(entity.getConditionJson()))
                .action(readTree(entity.getActionJson()))
                .status(entity.getStatus())
                .linkedObjectCount(countLinks(entity.getId()))
                .createdBy(entity.getCreatedBy() != null ? entity.getCreatedBy().toString() : null)
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().format(FORMATTER) : null)
                .updatedAt(entity.getUpdatedAt() != null ? entity.getUpdatedAt().format(FORMATTER) : null)
                .build();
    }

    private JsonNode readTree(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception ex) {
            return null;
        }
    }
}
