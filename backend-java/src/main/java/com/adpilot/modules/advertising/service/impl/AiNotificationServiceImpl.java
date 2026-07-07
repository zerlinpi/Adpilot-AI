package com.adpilot.modules.advertising.service.impl;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.common.security.CurrentUser;
import com.adpilot.common.security.DataScopeService;
import com.adpilot.common.security.ScopeTarget;
import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.advertising.converter.AiNotificationConverter;
import com.adpilot.modules.advertising.dto.AiNotificationConfigRequest;
import com.adpilot.modules.advertising.entity.AiNotificationConfigEntity;
import com.adpilot.modules.advertising.entity.AiNotificationEntity;
import com.adpilot.modules.advertising.mapper.AiNotificationConfigMapper;
import com.adpilot.modules.advertising.mapper.AiNotificationMapper;
import com.adpilot.modules.advertising.service.AiNotificationService;
import com.adpilot.modules.advertising.support.AiNotificationStateMachine;
import com.adpilot.modules.advertising.support.AiNotificationStateMachine.Resolution;
import com.adpilot.modules.advertising.support.AiNotificationStateMachine.State;
import com.adpilot.modules.advertising.support.AiNotificationStateMachine.Status;
import com.adpilot.modules.advertising.vo.AiNotificationConfigVo;
import com.adpilot.modules.advertising.vo.AiNotificationOverviewVo;
import com.adpilot.modules.advertising.vo.AiNotificationVo;
import com.adpilot.modules.feishu.service.FeishuService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * AI Notifications implementation (Req 23). Reuses the pure
 * {@link AiNotificationStateMachine} for the pending → closed transition so the
 * close behaviour (idempotent, terminal, resolution-carrying) is identical to
 * what Property 7 validates. The store-scoped overview groups items into the
 * four fixed categories with pending/closed counts and lists.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiNotificationServiceImpl implements AiNotificationService {

    private final AiNotificationMapper notificationMapper;
    private final AiNotificationConfigMapper configMapper;
    private final DataScopeService dataScopeService;
    private final ObjectMapper objectMapper;
    private final FeishuService feishuService;

    /** Store-scoped access target (ai_notifications has no owner column). */
    private static final ScopeTarget NOTIFICATION_SCOPE = ScopeTarget.store("store_id");

    /** The four fixed categories in display order, as {key, label} pairs (Req 23.1). */
    private static final String[][] CATEGORIES = {
            {"core_ops", "广告运营核心关注"},
            {"one_click_optimize", "广告活动一键优化"},
            {"high_potential", "发现高潜广告活动"},
            {"target_correction", "AI目标修正待确认"},
    };

    private static CurrentUser scopeUser() {
        return SecurityUtils.isAuthenticated() ? SecurityUtils.getCurrentUser() : null;
    }

    @Override
    public AiNotificationOverviewVo getOverview(String storeId) {
        QueryWrapper<AiNotificationEntity> wrapper = new QueryWrapper<>();
        if (storeId != null && !storeId.isBlank()) {
            wrapper.eq("store_id", parseUuid(storeId, "storeId"));
        }
        CurrentUser user = scopeUser();
        if (user != null) {
            dataScopeService.applyScope(wrapper, NOTIFICATION_SCOPE, user);
        }
        wrapper.orderByDesc("created_at");

        List<AiNotificationEntity> all = notificationMapper.selectList(wrapper);

        // Bucket items by category → pending/closed lists, preserving order.
        Map<String, List<AiNotificationVo>> pendingByCat = new LinkedHashMap<>();
        Map<String, List<AiNotificationVo>> closedByCat = new LinkedHashMap<>();
        for (String[] def : CATEGORIES) {
            pendingByCat.put(def[0], new ArrayList<>());
            closedByCat.put(def[0], new ArrayList<>());
        }
        for (AiNotificationEntity e : all) {
            String cat = e.getCategory();
            // Ignore any unknown category so the four-category view stays stable.
            if (!pendingByCat.containsKey(cat)) {
                continue;
            }
            AiNotificationVo vo = AiNotificationConverter.toVo(e, objectMapper);
            if (State.CLOSED.name().equalsIgnoreCase(e.getState())
                    || "closed".equalsIgnoreCase(e.getState())) {
                closedByCat.get(cat).add(vo);
            } else {
                pendingByCat.get(cat).add(vo);
            }
        }

        List<AiNotificationOverviewVo.Category> categories = new ArrayList<>();
        for (String[] def : CATEGORIES) {
            List<AiNotificationVo> pending = pendingByCat.get(def[0]);
            List<AiNotificationVo> closed = closedByCat.get(def[0]);
            categories.add(AiNotificationOverviewVo.Category.builder()
                    .key(def[0])
                    .label(def[1])
                    .pendingCount(pending.size())
                    .closedCount(closed.size())
                    .pending(pending)
                    .closed(closed)
                    .build());
        }
        return AiNotificationOverviewVo.builder().categories(categories).build();
    }

    @Override
    @Transactional
    public AiNotificationVo apply(String id) {
        return closeWith(id, Resolution.APPLIED);
    }

    @Override
    @Transactional
    public AiNotificationVo confirm(String id) {
        return closeWith(id, Resolution.CONFIRMED);
    }

    @Override
    @Transactional
    public AiNotificationVo reject(String id) {
        return closeWith(id, Resolution.REJECTED);
    }

    /**
     * Close a notification with the given resolution via the pure state machine.
     * Re-closing an already-closed item is a no-op (idempotent/terminal) and
     * returns the item unchanged, mirroring Property 7.
     */
    private AiNotificationVo closeWith(String id, Resolution resolution) {
        AiNotificationEntity entity = notificationMapper.selectById(parseUuid(id, "id"));
        if (entity == null) {
            throw new BusinessException("NOTIFICATION_NOT_FOUND", "AI notification not found: " + id);
        }
        CurrentUser user = scopeUser();
        if (user != null) {
            dataScopeService.assertCanRead(entity, user);
        }

        Status current = currentStatus(entity);
        Status next = AiNotificationStateMachine.close(current, resolution);

        // Only persist a real transition (pending → closed). A no-op close on an
        // already-closed item leaves the row untouched.
        if (current.isPending() && next.isClosed()) {
            entity.setState(State.CLOSED.name().toLowerCase());
            entity.setResolution(next.resolution().name().toLowerCase());
            entity.setClosedAt(LocalDateTime.now());
            notificationMapper.updateById(entity);
            log.info("AI notification {} closed with resolution {}", id, next.resolution());
            // Push the close event to the store's bound Feishu chat(s) (item 19).
            // Best-effort: a Feishu failure must not roll back the close.
            pushToFeishuQuietly(entity, "已处理（" + next.resolution().name().toLowerCase() + "）");
        }
        return AiNotificationConverter.toVo(entity, objectMapper);
    }

    @Override
    public AiNotificationVo pushToFeishu(String id) {
        AiNotificationEntity entity = notificationMapper.selectById(parseUuid(id, "id"));
        if (entity == null) {
            throw new BusinessException("NOTIFICATION_NOT_FOUND", "AI notification not found: " + id);
        }
        CurrentUser user = scopeUser();
        if (user != null) {
            dataScopeService.assertCanRead(entity, user);
        }
        String state = "closed".equalsIgnoreCase(entity.getState())
                ? "已结束（" + (entity.getResolution() != null ? entity.getResolution() : "") + "）"
                : "待处理";
        pushToFeishuQuietly(entity, state);
        return AiNotificationConverter.toVo(entity, objectMapper);
    }

    /**
     * Send the notification to the store's bound Feishu chat(s) via the shared
     * {@link FeishuService}. Swallows any failure so the caller's primary action
     * (close / manual push) is never affected.
     */
    private void pushToFeishuQuietly(AiNotificationEntity entity, String statusText) {
        try {
            String message = "类别：" + (entity.getCategory() != null ? entity.getCategory() : "-")
                    + "　状态：" + statusText
                    + (entity.getSubjectId() != null ? "　对象：" + entity.getSubjectId() : "");
            feishuService.pushAiNotification(entity.getStoreId(), entity.getTitle(), message);
        } catch (Exception e) {
            log.warn("Feishu push for AI notification {} failed: {}", entity.getId(), e.getMessage());
        }
    }

    @Override
    public AiNotificationConfigVo getConfig(String storeId) {
        AiNotificationConfigEntity entity = findConfig(storeId);
        if (entity == null) {
            // No config yet — return an empty, valid view rather than failing so
            // the configuration interface can render defaults (Req 23.5).
            return AiNotificationConfigVo.builder().storeId(storeId).build();
        }
        return AiNotificationConverter.toConfigVo(entity, objectMapper);
    }

    @Override
    @Transactional
    public AiNotificationConfigVo updateConfig(AiNotificationConfigRequest request) {
        UUID storeUuid = parseUuid(request.getStoreId(), "storeId");
        String configJson;
        try {
            configJson = objectMapper.writeValueAsString(request.getConfig());
        } catch (Exception ex) {
            throw new BusinessException("INVALID_CONFIG", "Invalid notification configuration payload");
        }

        AiNotificationConfigEntity entity = findConfig(request.getStoreId());
        if (entity == null) {
            entity = AiNotificationConfigEntity.builder()
                    .storeId(storeUuid)
                    .configJson(configJson)
                    .build();
            configMapper.insert(entity);
        } else {
            entity.setConfigJson(configJson);
            configMapper.updateById(entity);
        }
        log.info("AI notification config updated for store {}", request.getStoreId());
        return AiNotificationConverter.toConfigVo(entity, objectMapper);
    }

    private AiNotificationConfigEntity findConfig(String storeId) {
        QueryWrapper<AiNotificationConfigEntity> wrapper = new QueryWrapper<>();
        wrapper.eq("store_id", parseUuid(storeId, "storeId"));
        return configMapper.selectOne(wrapper);
    }

    /** Reconstruct the pure {@link Status} from the persisted state/resolution. */
    private Status currentStatus(AiNotificationEntity entity) {
        boolean closed = "closed".equalsIgnoreCase(entity.getState());
        if (!closed) {
            return AiNotificationStateMachine.pending();
        }
        Resolution resolution = parseResolution(entity.getResolution());
        return new Status(State.CLOSED, resolution);
    }

    private Resolution parseResolution(String value) {
        if (value == null || value.isBlank()) {
            // A closed row must carry a resolution; default to dismissed if the
            // legacy/imported row lacks one so the Status invariant holds.
            return Resolution.DISMISSED;
        }
        try {
            return Resolution.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return Resolution.DISMISSED;
        }
    }

    private UUID parseUuid(String value, String field) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new BusinessException("INVALID_ID", "Invalid " + field + ": " + value);
        }
    }
}
