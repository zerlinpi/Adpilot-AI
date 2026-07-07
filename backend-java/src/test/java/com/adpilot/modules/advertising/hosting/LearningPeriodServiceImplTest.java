package com.adpilot.modules.advertising.hosting;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the LearningPeriodServiceImpl (Requirement 19).
 *
 * <p>Validates:
 * <ul>
 *   <li>Req 19.1: Learning period starts when campaign is first hosted</li>
 *   <li>Req 19.2: Bid changes capped at 10%, budget changes blocked</li>
 *   <li>Req 19.3: Full rules apply after period completes</li>
 *   <li>Req 19.4: Personality change restarts the period</li>
 *   <li>Req 19.5: Days-remaining exposed</li>
 * </ul>
 */
@DisplayName("LearningPeriodServiceImpl")
class LearningPeriodServiceImplTest {

    private CampaignLearningPeriodMapper learningPeriodMapper;
    private LearningPeriodServiceImpl service;

    private static final UUID CAMPAIGN_ID = UUID.randomUUID();
    private static final UUID STORE_ID = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        learningPeriodMapper = mock(CampaignLearningPeriodMapper.class);
        service = new LearningPeriodServiceImpl(learningPeriodMapper);
    }

    @Nested
    @DisplayName("getStatus()")
    class GetStatus {

        @Test
        @DisplayName("returns notInPeriod when no record exists")
        void noRecord() {
            when(learningPeriodMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

            LearningPeriodStatus status = service.getStatus(CAMPAIGN_ID);

            assertThat(status.inLearningPeriod()).isFalse();
            assertThat(status.daysRemaining()).isZero();
        }

        @Test
        @DisplayName("returns active status with days remaining when within period")
        void withinPeriod() {
            CampaignLearningPeriodEntity entity = CampaignLearningPeriodEntity.builder()
                    .campaignId(CAMPAIGN_ID)
                    .storeId(STORE_ID)
                    .startDate(LocalDate.now())
                    .personalityAtStart("balanced")
                    .learningPeriodDays(3)
                    .build();
            when(learningPeriodMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(entity);

            LearningPeriodStatus status = service.getStatus(CAMPAIGN_ID);

            assertThat(status.inLearningPeriod()).isTrue();
            assertThat(status.daysRemaining()).isEqualTo(3);
            assertThat(status.startDate()).isEqualTo(LocalDate.now());
            assertThat(status.totalDays()).isEqualTo(3);
            assertThat(status.personalityAtStart()).isEqualTo("balanced");
        }

        @Test
        @DisplayName("returns notInPeriod when learning period has expired")
        void expired() {
            CampaignLearningPeriodEntity entity = CampaignLearningPeriodEntity.builder()
                    .campaignId(CAMPAIGN_ID)
                    .storeId(STORE_ID)
                    .startDate(LocalDate.now().minusDays(5))
                    .personalityAtStart("aggressive")
                    .learningPeriodDays(3)
                    .build();
            when(learningPeriodMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(entity);

            LearningPeriodStatus status = service.getStatus(CAMPAIGN_ID);

            assertThat(status.inLearningPeriod()).isFalse();
            assertThat(status.daysRemaining()).isZero();
        }

        @Test
        @DisplayName("returns notInPeriod for null campaignId")
        void nullCampaignId() {
            LearningPeriodStatus status = service.getStatus(null);
            assertThat(status.inLearningPeriod()).isFalse();
        }
    }

    @Nested
    @DisplayName("isInLearningPeriod()")
    class IsInLearningPeriod {

        @Test
        @DisplayName("returns true when campaign is within learning period")
        void withinPeriod() {
            CampaignLearningPeriodEntity entity = CampaignLearningPeriodEntity.builder()
                    .campaignId(CAMPAIGN_ID)
                    .storeId(STORE_ID)
                    .startDate(LocalDate.now().minusDays(1))
                    .personalityAtStart("balanced")
                    .learningPeriodDays(3)
                    .build();
            when(learningPeriodMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(entity);

            assertThat(service.isInLearningPeriod(CAMPAIGN_ID)).isTrue();
        }

        @Test
        @DisplayName("returns false when period has expired")
        void periodExpired() {
            CampaignLearningPeriodEntity entity = CampaignLearningPeriodEntity.builder()
                    .campaignId(CAMPAIGN_ID)
                    .storeId(STORE_ID)
                    .startDate(LocalDate.now().minusDays(10))
                    .personalityAtStart("balanced")
                    .learningPeriodDays(3)
                    .build();
            when(learningPeriodMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(entity);

            assertThat(service.isInLearningPeriod(CAMPAIGN_ID)).isFalse();
        }
    }

    @Nested
    @DisplayName("startOrRestart()")
    class StartOrRestart {

        @Test
        @DisplayName("creates new record when none exists")
        void createsNewRecord() {
            when(learningPeriodMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
            when(learningPeriodMapper.insert(any())).thenReturn(1);

            service.startOrRestart(CAMPAIGN_ID, STORE_ID, "aggressive", 5);

            verify(learningPeriodMapper).insert(any(CampaignLearningPeriodEntity.class));
            verify(learningPeriodMapper, never()).updateById(any());
        }

        @Test
        @DisplayName("updates existing record on restart")
        void updatesExistingRecord() {
            CampaignLearningPeriodEntity existing = CampaignLearningPeriodEntity.builder()
                    .id(UUID.randomUUID())
                    .campaignId(CAMPAIGN_ID)
                    .storeId(STORE_ID)
                    .startDate(LocalDate.now().minusDays(5))
                    .personalityAtStart("balanced")
                    .learningPeriodDays(3)
                    .build();
            when(learningPeriodMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);
            when(learningPeriodMapper.updateById(any())).thenReturn(1);

            service.startOrRestart(CAMPAIGN_ID, STORE_ID, "aggressive", 7);

            verify(learningPeriodMapper).updateById(any(CampaignLearningPeriodEntity.class));
            assertThat(existing.getPersonalityAtStart()).isEqualTo("aggressive");
            assertThat(existing.getStartDate()).isEqualTo(LocalDate.now());
            assertThat(existing.getLearningPeriodDays()).isEqualTo(7);
        }

        @Test
        @DisplayName("uses default days when 0 is passed")
        void defaultDaysOnZero() {
            when(learningPeriodMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
            when(learningPeriodMapper.insert(any())).thenReturn(1);

            service.startOrRestart(CAMPAIGN_ID, STORE_ID, "balanced", 0);

            verify(learningPeriodMapper).insert(argThat(entity ->
                    entity.getLearningPeriodDays() == LearningPeriodService.DEFAULT_LEARNING_PERIOD_DAYS));
        }
    }

    @Nested
    @DisplayName("checkAndRestartOnPersonalityChange()")
    class CheckAndRestart {

        @Test
        @DisplayName("restarts when personality has changed (Req 19.4)")
        void restartsOnChange() {
            CampaignLearningPeriodEntity existing = CampaignLearningPeriodEntity.builder()
                    .id(UUID.randomUUID())
                    .campaignId(CAMPAIGN_ID)
                    .storeId(STORE_ID)
                    .startDate(LocalDate.now().minusDays(1))
                    .personalityAtStart("balanced")
                    .learningPeriodDays(3)
                    .build();
            // First call returns existing, second call (within startOrRestart) also returns it
            when(learningPeriodMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);
            when(learningPeriodMapper.updateById(any())).thenReturn(1);

            boolean restarted = service.checkAndRestartOnPersonalityChange(
                    CAMPAIGN_ID, STORE_ID, "aggressive");

            assertThat(restarted).isTrue();
        }

        @Test
        @DisplayName("does not restart when personality is the same")
        void noRestartWhenSame() {
            CampaignLearningPeriodEntity existing = CampaignLearningPeriodEntity.builder()
                    .id(UUID.randomUUID())
                    .campaignId(CAMPAIGN_ID)
                    .storeId(STORE_ID)
                    .startDate(LocalDate.now().minusDays(1))
                    .personalityAtStart("balanced")
                    .learningPeriodDays(3)
                    .build();
            when(learningPeriodMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);

            boolean restarted = service.checkAndRestartOnPersonalityChange(
                    CAMPAIGN_ID, STORE_ID, "balanced");

            assertThat(restarted).isFalse();
            verify(learningPeriodMapper, never()).updateById(any());
            verify(learningPeriodMapper, never()).insert(any());
        }

        @Test
        @DisplayName("starts new period when no record exists")
        void startsNewWhenNoneExists() {
            when(learningPeriodMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
            when(learningPeriodMapper.insert(any())).thenReturn(1);

            boolean restarted = service.checkAndRestartOnPersonalityChange(
                    CAMPAIGN_ID, STORE_ID, "conservative");

            assertThat(restarted).isTrue();
            verify(learningPeriodMapper).insert(any(CampaignLearningPeriodEntity.class));
        }
    }

    @Nested
    @DisplayName("clampBidForLearningPeriod()")
    class ClampBid {

        @Test
        @DisplayName("does not clamp when change is within 10%")
        void withinLimit() {
            BigDecimal currentBid = new BigDecimal("1.00");
            BigDecimal proposedBid = new BigDecimal("1.08"); // 8% increase

            BigDecimal result = service.clampBidForLearningPeriod(currentBid, proposedBid);

            assertThat(result).isEqualByComparingTo(proposedBid);
        }

        @Test
        @DisplayName("clamps bid increase to 10% (Req 19.2)")
        void clampsIncrease() {
            BigDecimal currentBid = new BigDecimal("1.00");
            BigDecimal proposedBid = new BigDecimal("1.25"); // 25% increase

            BigDecimal result = service.clampBidForLearningPeriod(currentBid, proposedBid);

            assertThat(result).isEqualByComparingTo(new BigDecimal("1.1000"));
        }

        @Test
        @DisplayName("clamps bid decrease to 10% (Req 19.2)")
        void clampsDecrease() {
            BigDecimal currentBid = new BigDecimal("1.00");
            BigDecimal proposedBid = new BigDecimal("0.70"); // 30% decrease

            BigDecimal result = service.clampBidForLearningPeriod(currentBid, proposedBid);

            assertThat(result).isEqualByComparingTo(new BigDecimal("0.9000"));
        }

        @Test
        @DisplayName("handles exactly 10% change without clamping")
        void exactly10Percent() {
            BigDecimal currentBid = new BigDecimal("2.00");
            BigDecimal proposedBid = new BigDecimal("2.20"); // exactly 10%

            BigDecimal result = service.clampBidForLearningPeriod(currentBid, proposedBid);

            assertThat(result).isEqualByComparingTo(new BigDecimal("2.20"));
        }

        @Test
        @DisplayName("returns proposedBid when currentBid is zero or null")
        void handleNullOrZero() {
            assertThat(service.clampBidForLearningPeriod(null, new BigDecimal("1.00")))
                    .isEqualByComparingTo(new BigDecimal("1.00"));
            assertThat(service.clampBidForLearningPeriod(BigDecimal.ZERO, new BigDecimal("1.00")))
                    .isEqualByComparingTo(new BigDecimal("1.00"));
        }
    }

    @Nested
    @DisplayName("shouldBlockBudgetChanges()")
    class BlockBudget {

        @Test
        @DisplayName("blocks budget changes when in learning period (Req 19.2)")
        void blocksWhenInPeriod() {
            CampaignLearningPeriodEntity entity = CampaignLearningPeriodEntity.builder()
                    .campaignId(CAMPAIGN_ID)
                    .storeId(STORE_ID)
                    .startDate(LocalDate.now())
                    .personalityAtStart("balanced")
                    .learningPeriodDays(3)
                    .build();
            when(learningPeriodMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(entity);

            assertThat(service.shouldBlockBudgetChanges(CAMPAIGN_ID)).isTrue();
        }

        @Test
        @DisplayName("allows budget changes when period has expired (Req 19.3)")
        void allowsAfterExpiry() {
            CampaignLearningPeriodEntity entity = CampaignLearningPeriodEntity.builder()
                    .campaignId(CAMPAIGN_ID)
                    .storeId(STORE_ID)
                    .startDate(LocalDate.now().minusDays(10))
                    .personalityAtStart("balanced")
                    .learningPeriodDays(3)
                    .build();
            when(learningPeriodMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(entity);

            assertThat(service.shouldBlockBudgetChanges(CAMPAIGN_ID)).isFalse();
        }
    }

    @Nested
    @DisplayName("getStatuses()")
    class BatchStatuses {

        @Test
        @DisplayName("returns statuses for multiple campaigns (Req 19.5)")
        void batchQuery() {
            UUID campaignId2 = UUID.randomUUID();
            CampaignLearningPeriodEntity entity1 = CampaignLearningPeriodEntity.builder()
                    .campaignId(CAMPAIGN_ID)
                    .storeId(STORE_ID)
                    .startDate(LocalDate.now())
                    .personalityAtStart("balanced")
                    .learningPeriodDays(3)
                    .build();
            when(learningPeriodMapper.selectOne(any(LambdaQueryWrapper.class)))
                    .thenReturn(entity1)
                    .thenReturn(null);

            List<LearningPeriodStatus> statuses = service.getStatuses(
                    List.of(CAMPAIGN_ID, campaignId2));

            assertThat(statuses).hasSize(2);
            assertThat(statuses.get(0).inLearningPeriod()).isTrue();
            assertThat(statuses.get(1).inLearningPeriod()).isFalse();
        }
    }
}
