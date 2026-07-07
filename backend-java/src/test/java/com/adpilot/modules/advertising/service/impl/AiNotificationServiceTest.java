package com.adpilot.modules.advertising.service.impl;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.common.security.DataScopeService;
import com.adpilot.modules.advertising.dto.AiNotificationConfigRequest;
import com.adpilot.modules.advertising.entity.AiNotificationConfigEntity;
import com.adpilot.modules.advertising.entity.AiNotificationEntity;
import com.adpilot.modules.advertising.mapper.AiNotificationConfigMapper;
import com.adpilot.modules.advertising.mapper.AiNotificationMapper;
import com.adpilot.modules.advertising.vo.AiNotificationConfigVo;
import com.adpilot.modules.advertising.vo.AiNotificationOverviewVo;
import com.adpilot.modules.advertising.vo.AiNotificationVo;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AiNotificationServiceImpl} (Req 23). Covers the
 * four-category overview bucketing/counts, the apply/confirm/reject close
 * transitions (including idempotent no-op on an already-closed item), and the
 * config upsert/empty-view paths. Uses a real {@link ObjectMapper} so JSON
 * round-trips match production behaviour.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AiNotificationServiceTest {

    @Mock
    private AiNotificationMapper notificationMapper;
    @Mock
    private AiNotificationConfigMapper configMapper;
    @Mock
    private DataScopeService dataScopeService;
    @Mock
    private com.adpilot.modules.feishu.service.FeishuService feishuService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private AiNotificationServiceImpl newService() {
        return new AiNotificationServiceImpl(notificationMapper, configMapper, dataScopeService, objectMapper,
                feishuService);
    }

    private AiNotificationEntity entity(String category, String state, String resolution) {
        return AiNotificationEntity.builder()
                .id(UUID.randomUUID())
                .storeId(UUID.randomUUID())
                .category(category)
                .title("title")
                .state(state)
                .resolution(resolution)
                .build();
    }

    @Test
    void overviewBucketsAllFourCategoriesWithCounts() {
        String storeId = UUID.randomUUID().toString();
        when(notificationMapper.selectList(any())).thenReturn(List.of(
                entity("core_ops", "pending", null),
                entity("core_ops", "pending", null),
                entity("core_ops", "closed", "applied"),
                entity("one_click_optimize", "pending", null),
                entity("target_correction", "closed", "confirmed"),
                entity("unknown_category", "pending", null) // ignored
        ));

        AiNotificationOverviewVo overview = newService().getOverview(storeId);

        assertThat(overview.getCategories()).hasSize(4);
        assertThat(overview.getCategories())
                .extracting(AiNotificationOverviewVo.Category::getKey)
                .containsExactly("core_ops", "one_click_optimize", "high_potential", "target_correction");

        AiNotificationOverviewVo.Category coreOps = overview.getCategories().get(0);
        assertThat(coreOps.getPendingCount()).isEqualTo(2);
        assertThat(coreOps.getClosedCount()).isEqualTo(1);
        assertThat(coreOps.getPending()).hasSize(2);
        assertThat(coreOps.getClosed()).hasSize(1);

        AiNotificationOverviewVo.Category highPotential = overview.getCategories().get(2);
        assertThat(highPotential.getPendingCount()).isZero();
        assertThat(highPotential.getClosedCount()).isZero();
    }

    @Test
    void applyClosesPendingItemWithAppliedResolution() {
        AiNotificationEntity e = entity("one_click_optimize", "pending", null);
        when(notificationMapper.selectById(any())).thenReturn(e);

        AiNotificationVo vo = newService().apply(e.getId().toString());

        assertThat(vo.getState()).isEqualTo("closed");
        assertThat(vo.getResolution()).isEqualTo("applied");
        assertThat(e.getClosedAt()).isNotNull();
        verify(notificationMapper).updateById(e);
    }

    @Test
    void confirmAndRejectRecordTheirResolution() {
        AiNotificationEntity confirmTarget = entity("target_correction", "pending", null);
        when(notificationMapper.selectById(any())).thenReturn(confirmTarget);
        assertThat(newService().confirm(confirmTarget.getId().toString()).getResolution()).isEqualTo("confirmed");

        AiNotificationEntity rejectTarget = entity("target_correction", "pending", null);
        when(notificationMapper.selectById(any())).thenReturn(rejectTarget);
        assertThat(newService().reject(rejectTarget.getId().toString()).getResolution()).isEqualTo("rejected");
    }

    @Test
    void closingAnAlreadyClosedItemIsNoOp() {
        AiNotificationEntity e = entity("one_click_optimize", "closed", "applied");
        when(notificationMapper.selectById(any())).thenReturn(e);

        AiNotificationVo vo = newService().reject(e.getId().toString());

        // Terminal & idempotent: resolution stays "applied", no second write.
        assertThat(vo.getState()).isEqualTo("closed");
        assertThat(vo.getResolution()).isEqualTo("applied");
        verify(notificationMapper, never()).updateById(any());
    }

    @Test
    void applyFailsWhenNotificationMissing() {
        when(notificationMapper.selectById(any())).thenReturn(null);
        assertThatThrownBy(() -> newService().apply(UUID.randomUUID().toString()))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void getConfigReturnsEmptyViewWhenNoneExists() {
        String storeId = UUID.randomUUID().toString();
        when(configMapper.selectOne(any())).thenReturn(null);

        AiNotificationConfigVo vo = newService().getConfig(storeId);

        assertThat(vo.getStoreId()).isEqualTo(storeId);
        assertThat(vo.getConfig()).isNull();
    }

    @Test
    void updateConfigInsertsWhenAbsent() throws Exception {
        String storeId = UUID.randomUUID().toString();
        when(configMapper.selectOne(any())).thenReturn(null);

        AiNotificationConfigRequest req = new AiNotificationConfigRequest();
        req.setStoreId(storeId);
        req.setConfig(objectMapper.valueToTree(Map.of("budgetAlerts", true)));

        AiNotificationConfigVo vo = newService().updateConfig(req);

        assertThat(vo.getConfig().get("budgetAlerts").asBoolean()).isTrue();
        verify(configMapper).insert(any(AiNotificationConfigEntity.class));
    }

    @Test
    void updateConfigUpdatesWhenPresent() {
        String storeId = UUID.randomUUID().toString();
        AiNotificationConfigEntity existing = AiNotificationConfigEntity.builder()
                .id(UUID.randomUUID())
                .storeId(UUID.fromString(storeId))
                .configJson("{\"budgetAlerts\":false}")
                .build();
        when(configMapper.selectOne(any())).thenReturn(existing);

        AiNotificationConfigRequest req = new AiNotificationConfigRequest();
        req.setStoreId(storeId);
        req.setConfig(objectMapper.valueToTree(Map.of("budgetAlerts", true)));

        newService().updateConfig(req);

        verify(configMapper, times(1)).updateById(existing);
        verify(configMapper, never()).insert(any());
    }
}
