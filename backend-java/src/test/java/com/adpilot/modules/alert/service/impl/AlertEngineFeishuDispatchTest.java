package com.adpilot.modules.alert.service.impl;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.common.security.DataScopeService;
import com.adpilot.modules.alert.entity.AlertEntity;
import com.adpilot.modules.alert.enums.AlertType;
import com.adpilot.modules.alert.mapper.AlertMapper;
import com.adpilot.modules.alert.vo.AlertVo;
import com.adpilot.modules.feishu.service.FeishuService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AlertEngineImpl} covering Feishu push dispatch and the
 * BuyBox-loss / negative-review alert generators.
 *
 * <ul>
 *   <li>Req 10.1.3 — a BuyBox-loss alert is generated when BuyBox loss is detected.</li>
 *   <li>Req 10.1.4 — a negative-review alert is generated when a rating is at or below
 *       the negative-rating threshold.</li>
 *   <li>Req 10.1.6 — a generated alert is pushed to Feishu, and on success the alert is
 *       marked {@code feishu_pushed = 1} with no error.</li>
 *   <li>Req 10.1.9 — when the Feishu push fails the alert is retained in the alert center
 *       (still persisted) and the failure is recorded ({@code feishu_pushed = 0} and
 *       {@code feishu_error} set).</li>
 * </ul>
 *
 * <p>{@link AlertMapper} is mocked so no open alert pre-exists ({@code selectOne}
 * returns {@code null}) and {@code insert} assigns an id, mirroring the database's
 * generated key so the created entity can be asserted on. {@link FeishuService} is
 * mocked to simulate push success and failure. {@link DataScopeService} is unused by
 * these paths.</p>
 */
class AlertEngineFeishuDispatchTest {

    private static final String STATUS_OPEN = "open";

    private AlertMapper alertMapper;
    private FeishuService feishuService;
    private DataScopeService dataScopeService;

    private AlertEngineImpl engine;

    private final UUID storeId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        alertMapper = mock(AlertMapper.class);
        feishuService = mock(FeishuService.class);
        dataScopeService = mock(DataScopeService.class);

        // No open alert pre-exists for any condition under test.
        when(alertMapper.selectOne(any())).thenReturn(null);
        // insert assigns a generated id, as the database would.
        when(alertMapper.insert(any(AlertEntity.class))).thenAnswer(inv -> {
            AlertEntity e = inv.getArgument(0);
            if (e.getId() == null) {
                e.setId(UUID.randomUUID());
            }
            return 1;
        });
        when(alertMapper.updateById(any(AlertEntity.class))).thenReturn(1);

        engine = new AlertEngineImpl(alertMapper, feishuService, dataScopeService);
    }

    // ── Req 10.1.6: successful Feishu push marks feishu_pushed = 1 ──

    @Test
    void successfulFeishuPushMarksAlertPushed() {
        when(feishuService.pushAlert(eq(storeId), any(), any())).thenReturn(true);

        Optional<AlertVo> result = engine.evaluateBuyBoxLoss(storeId, "prod-1", true);

        // The alert was pushed to the configured Feishu destination (Req 10.1.6).
        verify(feishuService).pushAlert(eq(storeId), eq(AlertType.BUYBOX.code()), any());

        // After a successful push the alert is flagged pushed and carries no error.
        ArgumentCaptor<AlertEntity> captor = ArgumentCaptor.forClass(AlertEntity.class);
        verify(alertMapper).updateById(captor.capture());
        AlertEntity updated = captor.getValue();
        assertThat(updated.getFeishuPushed()).isTrue();
        assertThat(updated.getFeishuError()).isNull();

        assertThat(result).isPresent();
        assertThat(result.get().getFeishuPushed()).isTrue();
        assertThat(result.get().getFeishuError()).isNull();
    }

    @Test
    void unconfiguredFeishuDestinationLeavesAlertUnpushedWithoutError() {
        // pushAlert returns false when no destination is configured: nothing is sent
        // and no failure is recorded; the alert remains with feishu_pushed = 0.
        when(feishuService.pushAlert(eq(storeId), any(), any())).thenReturn(false);

        Optional<AlertVo> result = engine.evaluateBuyBoxLoss(storeId, "prod-1", true);

        // Only the initial insert happens; no update flips the pushed flag.
        verify(alertMapper).insert(any(AlertEntity.class));
        verify(alertMapper, never()).updateById(any(AlertEntity.class));

        assertThat(result).isPresent();
        assertThat(result.get().getFeishuPushed()).isFalse();
        assertThat(result.get().getFeishuError()).isNull();
    }

    // ── Req 10.1.9: failed Feishu push retains the alert and records the failure ──

    @Test
    void failedFeishuPushRetainsAlertAndRecordsFailure() {
        when(feishuService.pushAlert(eq(storeId), any(), any()))
                .thenThrow(new BusinessException("FEISHU_PUSH_FAILED", "destination unreachable"));

        Optional<AlertVo> result = engine.evaluateBuyBoxLoss(storeId, "prod-1", true);

        // The alert is still persisted (retained in the alert center) despite the failure.
        verify(alertMapper).insert(any(AlertEntity.class));

        // The push failure is recorded: feishu_pushed stays 0 and the error is captured.
        ArgumentCaptor<AlertEntity> captor = ArgumentCaptor.forClass(AlertEntity.class);
        verify(alertMapper).updateById(captor.capture());
        AlertEntity updated = captor.getValue();
        assertThat(updated.getFeishuPushed()).isFalse();
        assertThat(updated.getFeishuError()).isEqualTo("destination unreachable");
        assertThat(updated.getStatus()).isEqualTo(STATUS_OPEN);

        // The alert is returned to the caller (not lost) with the failure recorded.
        assertThat(result).isPresent();
        assertThat(result.get().getFeishuPushed()).isFalse();
        assertThat(result.get().getFeishuError()).isEqualTo("destination unreachable");
    }

    // ── Req 10.1.3: BuyBox-loss alert creation ──

    @Test
    void buyBoxLossCreatesAlertWhenBuyBoxLost() {
        when(feishuService.pushAlert(any(), any(), any())).thenReturn(true);

        Optional<AlertVo> result = engine.evaluateBuyBoxLoss(storeId, "prod-42", true);

        // A buybox alert is created for the affected store/product (Req 10.1.3).
        ArgumentCaptor<AlertEntity> captor = ArgumentCaptor.forClass(AlertEntity.class);
        verify(alertMapper).insert(captor.capture());
        AlertEntity created = captor.getValue();
        assertThat(created.getAlertType()).isEqualTo(AlertType.BUYBOX.code());
        assertThat(created.getStoreId()).isEqualTo(storeId);
        assertThat(created.getSubjectId()).isEqualTo("prod-42");
        assertThat(created.getStatus()).isEqualTo(STATUS_OPEN);
        assertThat(created.getMessage()).contains("prod-42");

        assertThat(result).isPresent();
        assertThat(result.get().getAlertType()).isEqualTo(AlertType.BUYBOX.code());
        assertThat(result.get().getStatus()).isEqualTo(STATUS_OPEN);
    }

    @Test
    void buyBoxLossCreatesNoAlertWhenBuyBoxHeld() {
        Optional<AlertVo> result = engine.evaluateBuyBoxLoss(storeId, "prod-42", false);

        // No loss detected -> no alert is generated and nothing is pushed.
        assertThat(result).isEmpty();
        verify(alertMapper, never()).insert(any(AlertEntity.class));
        verify(feishuService, never()).pushAlert(any(), any(), any());
    }

    // ── Req 10.1.4: negative-review alert creation ──

    @Test
    void negativeReviewCreatesAlertWhenRatingAtOrBelowThreshold() {
        when(feishuService.pushAlert(any(), any(), any())).thenReturn(true);

        // rating (2) is at or below the negative-rating threshold (2).
        Optional<AlertVo> result = engine.evaluateNegativeReview(storeId, "prod-7", 2, 2);

        ArgumentCaptor<AlertEntity> captor = ArgumentCaptor.forClass(AlertEntity.class);
        verify(alertMapper).insert(captor.capture());
        AlertEntity created = captor.getValue();
        assertThat(created.getAlertType()).isEqualTo(AlertType.NEGATIVE_REVIEW.code());
        assertThat(created.getStoreId()).isEqualTo(storeId);
        assertThat(created.getSubjectId()).isEqualTo("prod-7");
        assertThat(created.getStatus()).isEqualTo(STATUS_OPEN);

        assertThat(result).isPresent();
        assertThat(result.get().getAlertType()).isEqualTo(AlertType.NEGATIVE_REVIEW.code());
    }

    @Test
    void negativeReviewCreatesNoAlertWhenRatingAboveThreshold() {
        // rating (5) is above the negative-rating threshold (2): not a negative review.
        Optional<AlertVo> result = engine.evaluateNegativeReview(storeId, "prod-7", 5, 2);

        assertThat(result).isEmpty();
        verify(alertMapper, never()).insert(any(AlertEntity.class));
        verify(feishuService, never()).pushAlert(any(), any(), any());
    }
}
