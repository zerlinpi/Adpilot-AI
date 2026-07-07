package com.adpilot.modules.customer.service.impl;

import com.adpilot.common.api.PageResponse;
import com.adpilot.common.exception.BusinessException;
import com.adpilot.modules.customer.entity.BuyerMessageEntity;
import com.adpilot.modules.customer.entity.CustomerTicketEntity;
import com.adpilot.modules.customer.mapper.BuyerMessageMapper;
import com.adpilot.modules.customer.mapper.CustomerTicketMapper;
import com.adpilot.modules.customer.service.CustomerService;
import com.adpilot.modules.customer.vo.BuyerMessageVo;
import com.adpilot.modules.ai.service.AiAssistService;
import com.adpilot.modules.store.entity.StoreEntity;
import com.adpilot.modules.store.mapper.StoreMapper;
import com.adpilot.common.security.CurrentUser;
import com.adpilot.common.security.DataScopeService;
import com.adpilot.common.security.ScopeTarget;
import com.adpilot.common.utils.SecurityUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CustomerServiceImpl implements CustomerService {

    private final BuyerMessageMapper buyerMessageMapper;
    private final CustomerTicketMapper customerTicketMapper;
    private final AiAssistService aiAssistService;
    private final StoreMapper storeMapper;
    private final DataScopeService dataScopeService;

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * Store + store-group scope target for customer tickets (platform-workspace-rbac
     * Req 9.7 / 13.6). Tickets carry both {@code store_id} and the inherited
     * {@code store_group_id}, so the shared layer keeps tickets outside the operator's
     * Store_Group_Scope (or assigned-store scope) invisible through one predicate.
     */
    private static final ScopeTarget TICKET_SCOPE = ScopeTarget.builder()
            .storeIdColumn("store_id")
            .storeGroupIdColumn("store_group_id")
            .build();

    /** Current authenticated user, or {@code null} for system/background contexts. */
    private static CurrentUser scopeUser() {
        return SecurityUtils.isAuthenticated() ? SecurityUtils.getCurrentUser() : null;
    }

    @Override
    public PageResponse<BuyerMessageVo> listBuyerMessages(String storeId, int page, int pageSize) {
        Page<BuyerMessageEntity> pageParam = new Page<>(page, pageSize);
        LambdaQueryWrapper<BuyerMessageEntity> wrapper = new LambdaQueryWrapper<>();
        if (storeId != null && !storeId.isBlank()) {
            try { wrapper.eq(BuyerMessageEntity::getStoreId, UUID.fromString(storeId)); }
            catch (IllegalArgumentException ignored) { }
        }
        wrapper.orderByDesc(BuyerMessageEntity::getCreatedAt);

        Page<BuyerMessageEntity> result = buyerMessageMapper.selectPage(pageParam, wrapper);
        List<BuyerMessageVo> voList = result.getRecords().stream()
                .map(this::toMessageVo)
                .collect(Collectors.toList());

        return PageResponse.of(voList, result.getTotal(), page, pageSize);
    }

    @Override
    public BuyerMessageVo getBuyerMessageById(String id) {
        BuyerMessageEntity entity = buyerMessageMapper.selectById(UUID.fromString(id));
        if (entity == null) {
            throw new BusinessException("BUYER_MESSAGE_NOT_FOUND", "Buyer message not found: " + id);
        }
        return toMessageVo(entity);
    }

    @Override
    @Transactional
    public BuyerMessageVo replyToBuyerMessage(String id, String reply) {
        BuyerMessageEntity entity = buyerMessageMapper.selectById(UUID.fromString(id));
        if (entity == null) {
            throw new BusinessException("BUYER_MESSAGE_NOT_FOUND", "Buyer message not found: " + id);
        }

        entity.setReply(reply);
        entity.setRepliedAt(LocalDateTime.now());
        entity.setStatus("replied");
        entity.setUpdatedAt(LocalDateTime.now());

        buyerMessageMapper.updateById(entity);
        log.info("Buyer message replied: id={}", id);
        return toMessageVo(entity);
    }

    @Override
    public PageResponse<?> listCustomerTickets(String storeId, int page, int pageSize) {
        Page<CustomerTicketEntity> pageParam = new Page<>(page, pageSize);
        QueryWrapper<CustomerTicketEntity> wrapper = new QueryWrapper<>();
        if (storeId != null && !storeId.isBlank()) {
            try { wrapper.eq("store_id", UUID.fromString(storeId).toString()); }
            catch (IllegalArgumentException ignored) { }
        }
        // Enforce Store_Group_Scope through the shared data-scope layer so tickets whose
        // store group is outside the operator's scope are never displayed (Req 9.7).
        CurrentUser user = scopeUser();
        if (user != null) {
            dataScopeService.applyScope(wrapper, TICKET_SCOPE, user);
        }
        wrapper.orderByDesc("created_at");

        Page<CustomerTicketEntity> result = customerTicketMapper.selectPage(pageParam, wrapper);
        List<?> voList = result.getRecords().stream()
                .map(this::toTicketVo)
                .collect(Collectors.toList());

        return PageResponse.of(voList, result.getTotal(), page, pageSize);
    }

    @Override
    @Transactional
    public Object createCustomerTicket(Object dto) {
        Map<String, Object> data = asMap(dto);
        UUID storeId = requiredUuid(data, "storeId");
        String subject = requiredText(data, "subject");

        CustomerTicketEntity entity = CustomerTicketEntity.builder()
                .storeId(storeId)
                .orderId(text(data, "orderId", text(data, "orderNo", null)))
                .buyerEmail(text(data, "buyerEmail", null))
                .subject(subject)
                .description(text(data, "description", ""))
                .category(text(data, "category", "other"))
                .priority(normalizePriority(text(data, "priority", "medium")))
                .status(normalizeStatus(text(data, "status", "open")))
                .assignedTo(optionalUuid(data, "assignedTo"))
                .build();

        customerTicketMapper.insert(entity);
        log.info("Customer ticket created: id={}, storeId={}", entity.getId(), entity.getStoreId());
        return toTicketVo(entity);
    }

    @Override
    @Transactional
    public Object convertMessageToTicket(String messageId) {
        BuyerMessageEntity message = buyerMessageMapper.selectById(UUID.fromString(messageId));
        if (message == null) {
            throw new BusinessException("BUYER_MESSAGE_NOT_FOUND", "Buyer message not found: " + messageId);
        }

        // The ticket inherits its Store_Group from the originating Store so the shared
        // data-scope layer can keep it within the operator's Store_Group_Scope
        // (platform-workspace-rbac Req 9.2, 9.7).
        UUID storeGroupId = null;
        if (message.getStoreId() != null) {
            StoreEntity store = storeMapper.selectById(message.getStoreId());
            if (store != null) {
                storeGroupId = store.getStoreGroupId();
            }
        }

        CustomerTicketEntity ticket = CustomerTicketEntity.builder()
                .storeId(message.getStoreId())
                .storeGroupId(storeGroupId)
                .orderId(message.getOrderId())
                .buyerEmail(message.getBuyerEmail())
                .subject(message.getSubject() != null && !message.getSubject().isBlank()
                        ? message.getSubject() : "(无主题)")
                .description(message.getMessage() != null ? message.getMessage() : "")
                .category("other")
                .priority("medium")
                .status("pending")
                .build();

        customerTicketMapper.insert(ticket);
        log.info("Buyer message {} converted to ticket {} (storeId={}, storeGroupId={})",
                messageId, ticket.getId(), ticket.getStoreId(), storeGroupId);
        return toTicketVo(ticket);
    }

    @Override
    public Object generateReplyDraft(String ticketId) {
        CustomerTicketEntity entity = customerTicketMapper.selectById(UUID.fromString(ticketId));
        if (entity == null) {
            throw new BusinessException("TICKET_NOT_FOUND", "Customer ticket not found: " + ticketId);
        }

        String subject = entity.getSubject() != null ? entity.getSubject() : "(无主题)";
        String description = entity.getDescription() != null ? entity.getDescription() : "";

        String draft = null;
        String generatedBy = "template";
        if (aiAssistService.isEnabled()) {
            String system = "You are a professional Amazon seller customer-service agent. "
                    + "Write a concise, polite, empathetic reply to the buyer's ticket. "
                    + "Reply in the same language as the ticket. Do not invent order facts; "
                    + "use neutral placeholders if unknown. Output the reply text only, no preamble.";
            String user = "Ticket subject: " + subject + "\n"
                    + "Ticket category: " + (entity.getCategory() != null ? entity.getCategory() : "general") + "\n"
                    + "Buyer message: " + description;
            Optional<String> out = aiAssistService.generate("customer_reply_draft",
                    null, entity.getStoreId() != null ? entity.getStoreId().toString() : null, system, user);
            if (out.isPresent() && !out.get().isBlank()) {
                draft = out.get().trim();
                generatedBy = "ai";
            }
        }

        if (draft == null) {
            // Deterministic template fallback when AI is disabled/unavailable.
            draft = "您好，\n\n感谢您的联系，我们已收到关于「" + subject + "」的反馈。"
                    + "非常抱歉给您带来不便，我们正在核实相关情况，并会尽快为您妥善处理。"
                    + "如您能补充订单号或更多细节，将有助于我们更快地协助您。\n\n祝好，\n客服团队";
        }

        log.info("Generated reply draft for ticket {} (by {})", ticketId, generatedBy);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ticketId", ticketId);
        result.put("draft", draft);
        result.put("generatedBy", generatedBy);
        return result;
    }

    private BuyerMessageVo toMessageVo(BuyerMessageEntity entity) {
        return BuyerMessageVo.builder()
                .id(entity.getId().toString())
                .storeId(entity.getStoreId().toString())
                .orderId(entity.getOrderId())
                .buyerEmail(entity.getBuyerEmail())
                .subject(entity.getSubject())
                .message(entity.getMessage())
                .direction(entity.getDirection())
                .status(entity.getStatus())
                .reply(entity.getReply())
                .repliedAt(entity.getRepliedAt() != null ? entity.getRepliedAt().format(FORMATTER) : null)
                .rawData(entity.getRawData())
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().format(FORMATTER) : null)
                .updatedAt(entity.getUpdatedAt() != null ? entity.getUpdatedAt().format(FORMATTER) : null)
                .build();
    }

    private Object toTicketVo(CustomerTicketEntity entity) {
        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("id", entity.getId() != null ? entity.getId().toString() : null);
        vo.put("ticketNo", entity.getId() != null ? "CS-" + entity.getId().toString().substring(0, 8).toUpperCase() : null);
        vo.put("storeId", entity.getStoreId() != null ? entity.getStoreId().toString() : null);
        vo.put("orderId", entity.getOrderId());
        vo.put("orderNo", entity.getOrderId());
        vo.put("buyerEmail", entity.getBuyerEmail());
        vo.put("subject", entity.getSubject());
        vo.put("description", entity.getDescription());
        vo.put("category", entity.getCategory());
        vo.put("priority", entity.getPriority());
        vo.put("status", entity.getStatus());
        vo.put("assignedTo", entity.getAssignedTo() != null ? entity.getAssignedTo().toString() : null);
        vo.put("assignee", entity.getAssignedTo() != null ? entity.getAssignedTo().toString() : null);
        vo.put("resolvedAt", entity.getResolvedAt() != null ? entity.getResolvedAt().format(FORMATTER) : null);
        vo.put("createdAt", entity.getCreatedAt() != null ? entity.getCreatedAt().format(FORMATTER) : null);
        vo.put("updatedAt", entity.getUpdatedAt() != null ? entity.getUpdatedAt().format(FORMATTER) : null);
        return vo;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object dto) {
        if (dto instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new BusinessException("VALIDATION_ERROR", "Request body must be an object");
    }

    private UUID requiredUuid(Map<String, Object> data, String key) {
        String value = text(data, key, null);
        if (value == null) {
            throw new BusinessException("VALIDATION_ERROR", key + " is required");
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new BusinessException("VALIDATION_ERROR", key + " is invalid");
        }
    }

    private UUID optionalUuid(Map<String, Object> data, String key) {
        String value = text(data, key, null);
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new BusinessException("VALIDATION_ERROR", key + " is invalid");
        }
    }

    private String requiredText(Map<String, Object> data, String key) {
        String value = text(data, key, null);
        if (value == null || value.isBlank()) {
            throw new BusinessException("VALIDATION_ERROR", key + " is required");
        }
        return value;
    }

    private String text(Map<String, Object> data, String key, String fallback) {
        Object value = data.get(key);
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? fallback : text;
    }

    private String normalizePriority(String priority) {
        String p = priority != null ? priority.trim().toLowerCase() : "medium";
        if (p.equals("normal")) {
            return "medium";
        }
        return switch (p) {
            case "low", "medium", "high" -> p;
            default -> "medium";
        };
    }

    private String normalizeStatus(String status) {
        String s = status != null ? status.trim().toLowerCase() : "open";
        return switch (s) {
            case "open", "pending" -> "pending";
            case "in_progress", "resolved", "closed" -> s;
            default -> "pending";
        };
    }
}
