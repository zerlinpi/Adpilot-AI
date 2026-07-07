package com.adpilot.modules.alert.service.impl;

import com.adpilot.common.security.CurrentUser;
import com.adpilot.common.security.DataScopeService;
import com.adpilot.common.security.ScopeTarget;
import com.adpilot.modules.alert.entity.AlertEntity;
import com.adpilot.modules.alert.enums.AlertType;
import com.adpilot.modules.alert.mapper.AlertMapper;
import com.adpilot.modules.alert.model.AlertCondition;
import com.adpilot.modules.alert.service.AlertEngine;
import com.adpilot.modules.alert.vo.AlertVo;
import com.adpilot.modules.feishu.service.FeishuService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Default {@link AlertEngine} (Req 10.1). Reconciles evaluated conditions against
 * the {@code alerts} table: a single open alert is kept per (store, type, subject),
 * new alerts are pushed to Feishu where configured (failures recorded without losing
 * the alert), and display is scoped to the stores a user may access.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AlertEngineImpl implements AlertEngine {

    private static final String STATUS_OPEN = "open";
    private static final String STATUS_RESOLVED = "resolved";
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AlertMapper alertMapper;
    private final FeishuService feishuService;
    private final DataScopeService dataScopeService;

    // ------------------------------------------------------------------
    // Core dedup / resolve reconciliation (Req 10.1.7 / 10.1.8)
    // ------------------------------------------------------------------

    @Override
    @Transactional
    public Optional<AlertVo> evaluate(AlertCondition condition) {
        AlertEntity existing = findOpenAlert(condition.storeId(), condition.type().code(), condition.subjectId());

        if (condition.active()) {
            if (existing != null) {
                // Update the existing open alert in place rather than duplicating (Req 10.1.7).
                existing.setLastSeenAt(LocalDateTime.now());
                if (condition.message() != null) {
                    existing.setMessage(condition.message());
                }
                if (condition.severity() != null) {
                    existing.setSeverity(condition.severity());
                }
                alertMapper.updateById(existing);
                return Optional.of(toVo(existing));
            }
            // Create the single open alert for this condition (Req 10.1.1-10.1.4).
            AlertEntity created = createOpenAlert(condition);
            dispatchToFeishu(created);
            return Optional.of(toVo(created));
        }

        // Condition cleared: resolve any open alert (Req 10.1.8).
        if (existing != null) {
            existing.setStatus(STATUS_RESOLVED);
            existing.setResolvedAt(LocalDateTime.now());
            existing.setLastSeenAt(LocalDateTime.now());
            alertMapper.updateById(existing);
        }
        return Optional.empty();
    }

    // ------------------------------------------------------------------
    // Condition-specific generators (Req 10.1.1 - 10.1.4)
    // ------------------------------------------------------------------

    @Override
    public Optional<AlertVo> evaluateStockout(UUID storeId, String productId, Integer available, Integer threshold) {
        boolean active = available != null && threshold != null && available <= threshold;
        String message = active
                ? String.format("Stockout: available %d at or below threshold %d", available, threshold)
                : null;
        return evaluate(new AlertCondition(storeId, AlertType.STOCKOUT, productId, active,
                active ? "critical" : null, message));
    }

    @Override
    public Optional<AlertVo> evaluateAcos(UUID storeId, String campaignId, BigDecimal acos, BigDecimal threshold) {
        boolean active = false;
        String message = null;
        if (acos != null && threshold != null && acos.compareTo(threshold) > 0) {
            active = true;
            message = String.format("ACoS %s exceeds threshold %s",
                    acos.toPlainString(), threshold.toPlainString());
        }
        return evaluate(new AlertCondition(storeId, AlertType.ACOS, campaignId, active,
                active ? "warning" : null, message));
    }

    @Override
    public Optional<AlertVo> evaluateBuyBoxLoss(UUID storeId, String productId, boolean buyBoxLost) {
        String message = buyBoxLost ? "BuyBox lost for product " + productId : null;
        return evaluate(new AlertCondition(storeId, AlertType.BUYBOX, productId, buyBoxLost,
                buyBoxLost ? "warning" : null, message));
    }

    @Override
    public Optional<AlertVo> evaluateNegativeReview(UUID storeId, String productId, Integer rating,
                                                    Integer negativeRatingThreshold) {
        boolean active = rating != null && negativeRatingThreshold != null && rating <= negativeRatingThreshold;
        String message = active
                ? String.format("Negative review: rating %d at or below %d", rating, negativeRatingThreshold)
                : null;
        return evaluate(new AlertCondition(storeId, AlertType.NEGATIVE_REVIEW, productId, active,
                active ? "warning" : null, message));
    }

    // ------------------------------------------------------------------
    // Scoped display (Req 10.1.5)
    // ------------------------------------------------------------------

    @Override
    public List<AlertVo> listForUser(CurrentUser user, String status) {
        QueryWrapper<AlertEntity> wrapper = new QueryWrapper<>();
        if (status != null && !status.isBlank()) {
            wrapper.eq("status", status);
        }
        // Restrict to stores the user is permitted to access (Req 10.1.5).
        dataScopeService.applyScope(wrapper, ScopeTarget.store("store_id"), user);
        wrapper.orderByDesc("last_seen_at");

        return alertMapper.selectList(wrapper).stream()
                .map(this::toVo)
                .collect(Collectors.toList());
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private AlertEntity findOpenAlert(UUID storeId, String alertType, String subjectId) {
        QueryWrapper<AlertEntity> wrapper = new QueryWrapper<>();
        wrapper.eq("store_id", storeId.toString())
                .eq("alert_type", alertType)
                .eq("status", STATUS_OPEN);
        if (subjectId == null) {
            wrapper.isNull("subject_id");
        } else {
            wrapper.eq("subject_id", subjectId);
        }
        wrapper.last("LIMIT 1");
        return alertMapper.selectOne(wrapper);
    }

    private AlertEntity createOpenAlert(AlertCondition condition) {
        LocalDateTime now = LocalDateTime.now();
        AlertEntity entity = AlertEntity.builder()
                .storeId(condition.storeId())
                .alertType(condition.type().code())
                .subjectId(condition.subjectId())
                .severity(condition.severity() != null ? condition.severity() : "warning")
                .status(STATUS_OPEN)
                .message(condition.message())
                .feishuPushed(false)
                .firstSeenAt(now)
                .lastSeenAt(now)
                .build();
        alertMapper.insert(entity);
        return entity;
    }

    /**
     * Attempt to push a newly created alert to Feishu (Req 10.1.6). The alert has
     * already been persisted, so any push failure is recorded against it without
     * losing the alert (Req 10.1.9).
     */
    private void dispatchToFeishu(AlertEntity alert) {
        try {
            boolean pushed = feishuService.pushAlert(alert.getStoreId(),
                    alert.getAlertType(), alert.getMessage());
            if (pushed) {
                alert.setFeishuPushed(true);
                alert.setFeishuError(null);
                alertMapper.updateById(alert);
            }
            // pushed == false means no destination configured -> leave feishu_pushed = 0.
        } catch (Exception e) {
            // Record the push failure but keep the alert in the center (Req 10.1.9).
            log.warn("Feishu push failed for alert {} (retained): {}", alert.getId(), e.getMessage());
            alert.setFeishuPushed(false);
            alert.setFeishuError(truncate(e.getMessage(), 1000));
            alertMapper.updateById(alert);
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private AlertVo toVo(AlertEntity entity) {
        return AlertVo.builder()
                .id(entity.getId() != null ? entity.getId().toString() : null)
                .storeId(entity.getStoreId() != null ? entity.getStoreId().toString() : null)
                .alertType(entity.getAlertType())
                .subjectId(entity.getSubjectId())
                .severity(entity.getSeverity())
                .status(entity.getStatus())
                .message(entity.getMessage())
                .feishuPushed(entity.getFeishuPushed())
                .feishuError(entity.getFeishuError())
                .firstSeenAt(entity.getFirstSeenAt() != null ? entity.getFirstSeenAt().format(FORMATTER) : null)
                .lastSeenAt(entity.getLastSeenAt() != null ? entity.getLastSeenAt().format(FORMATTER) : null)
                .resolvedAt(entity.getResolvedAt() != null ? entity.getResolvedAt().format(FORMATTER) : null)
                .build();
    }
}
