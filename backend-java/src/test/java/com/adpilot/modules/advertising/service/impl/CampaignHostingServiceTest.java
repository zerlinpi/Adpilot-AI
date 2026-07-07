package com.adpilot.modules.advertising.service.impl;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.common.security.DataScopeService;
import com.adpilot.modules.advertising.dto.CampaignHostingRequest;
import com.adpilot.modules.advertising.entity.CampaignEntity;
import com.adpilot.modules.advertising.mapper.CampaignMapper;
import com.adpilot.modules.advertising.mapper.PerformanceDailyMapper;
import com.adpilot.modules.advertising.vo.CampaignVo;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the AI_Hosting assign / remove service paths (Req 21.1, 21.5,
 * 21.6) on {@link CampaignServiceImpl}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CampaignHostingServiceTest {

    @Mock
    private CampaignMapper campaignMapper;
    @Mock
    private PerformanceDailyMapper performanceDailyMapper;
    @Mock
    private DataScopeService dataScopeService;
    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private CampaignServiceImpl service;

    private CampaignEntity hostedFixture() {
        return CampaignEntity.builder()
                .id(UUID.randomUUID())
                .storeId(UUID.randomUUID())
                .name("Camp")
                .status("enabled")
                .build();
    }

    @Test
    void assignHostingPersistsGoalAndTargetAcos() {
        CampaignEntity entity = hostedFixture();
        when(campaignMapper.selectById(any())).thenReturn(entity);

        CampaignHostingRequest req = new CampaignHostingRequest();
        req.setTargetAcos(new BigDecimal("25.0"));
        req.setHostingGoal("maximize_sales_at_target");

        CampaignVo vo = service.assignHosting(entity.getId().toString(), req, null);

        assertThat(vo.isHostingEnabled()).isTrue();
        assertThat(vo.isAiManaged()).isTrue();
        assertThat(vo.getTargetAcos()).isEqualTo(25.0);
        assertThat(vo.getHostingGoal()).isEqualTo("maximize_sales_at_target");
        verify(campaignMapper).updateById(entity);
    }

    @Test
    void assignHostingDefaultsGoalWhenOmitted() {
        CampaignEntity entity = hostedFixture();
        when(campaignMapper.selectById(any())).thenReturn(entity);

        CampaignHostingRequest req = new CampaignHostingRequest();
        req.setTargetAcos(new BigDecimal("30"));

        CampaignVo vo = service.assignHosting(entity.getId().toString(), req, null);

        assertThat(vo.getHostingGoal()).isEqualTo("maximize_sales_at_target");
    }

    @Test
    void assignHostingRejectsMissingTargetAcos() {
        CampaignHostingRequest req = new CampaignHostingRequest();
        req.setTargetAcos(null);

        assertThatThrownBy(() -> service.assignHosting(UUID.randomUUID().toString(), req, null))
                .isInstanceOf(BusinessException.class);
        verify(campaignMapper, never()).updateById(any());
    }

    @Test
    void assignHostingRejectsNonPositiveTargetAcos() {
        CampaignHostingRequest req = new CampaignHostingRequest();
        req.setTargetAcos(BigDecimal.ZERO);

        assertThatThrownBy(() -> service.assignHosting(UUID.randomUUID().toString(), req, null))
                .isInstanceOf(BusinessException.class);
        verify(campaignMapper, never()).updateById(any());
    }

    @Test
    void assignHostingFailsWhenCampaignMissing() {
        when(campaignMapper.selectById(any())).thenReturn(null);
        CampaignHostingRequest req = new CampaignHostingRequest();
        req.setTargetAcos(new BigDecimal("25"));

        assertThatThrownBy(() -> service.assignHosting(UUID.randomUUID().toString(), req, null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void removeHostingClearsHostingState() {
        CampaignEntity entity = hostedFixture();
        entity.setHostingEnabled(true);
        entity.setAiManaged(true);
        entity.setHostingGoal("maximize_sales_at_target");
        entity.setTargetAcos(new BigDecimal("25"));
        when(campaignMapper.selectById(any())).thenReturn(entity);

        CampaignVo vo = service.removeHosting(entity.getId().toString(), null);

        assertThat(vo.isHostingEnabled()).isFalse();
        assertThat(vo.isAiManaged()).isFalse();
        assertThat(vo.getHostingGoal()).isNull();
        assertThat(vo.getTargetAcos()).isNull();
        verify(campaignMapper).updateById(entity);
    }
}
