package com.adpilot.modules.advertising.hosting;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Implementation of learning-period enforcement (Requirement 19).
 *
 * <p>Tracks learning period start dates per campaign in the
 * {@code campaign_learning_periods} table, determines whether a campaign
 * is within its learning period, and provides enforcement methods that
 * the V1 bid engine and V2 budget engine use to constrain their proposals.
 *
 * <p>Key behaviors:
 * <ul>
 *   <li>Req 19.1: Records hosting_start_date when a campaign is first hosted.</li>
 *   <li>Req 19.2: Caps bid changes at 10% and blocks budget changes during the period.</li>
 *   <li>Req 19.3: Full personality-driven rules apply after period completes.</li>
 *   <li>Req 19.4: Restarts the learning period when personality changes.</li>
 *   <li>Req 19.5: Exposes days-remaining for dashboard display.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LearningPeriodServiceImpl implements LearningPeriodService {

    private final CampaignLearningPeriodMapper learningPeriodMapper;

    @Override
    public LearningPeriodStatus getStatus(UUID campaignId) {
        if (campaignId == null) {
            return LearningPeriodStatus.notInPeriod();
        }

        CampaignLearningPeriodEntity entity = findByCampaignId(campaignId);
        if (entity == null) {
            return LearningPeriodStatus.notInPeriod();
        }

        return computeStatus(entity);
    }

    @Override
    public boolean isInLearningPeriod(UUID campaignId) {
        return getStatus(campaignId).inLearningPeriod();
    }

    @Override
    @Transactional
    public void startOrRestart(UUID campaignId, UUID storeId, String currentPersonality) {
        startOrRestart(campaignId, storeId, currentPersonality, DEFAULT_LEARNING_PERIOD_DAYS);
    }

    @Override
    @Transactional
    public void startOrRestart(UUID campaignId, UUID storeId, String currentPersonality,
                               int learningPeriodDays) {
        if (campaignId == null || storeId == null) {
            return;
        }

        int learningDays = learningPeriodDays > 0 ? learningPeriodDays : DEFAULT_LEARNING_PERIOD_DAYS;
        LocalDate today = LocalDate.now();

        CampaignLearningPeriodEntity existing = findByCampaignId(campaignId);
        if (existing != null) {
            // Update existing record (restart)
            existing.setStartDate(today);
            existing.setPersonalityAtStart(currentPersonality != null ? currentPersonality : "balanced");
            existing.setLearningPeriodDays(learningDays);
            learningPeriodMapper.updateById(existing);
            log.info("Learning period restarted for campaign={} with personality={}, duration={} days",
                    campaignId, currentPersonality, learningDays);
        } else {
            // Create new record
            CampaignLearningPeriodEntity entity = CampaignLearningPeriodEntity.builder()
                    .id(UUID.randomUUID())
                    .campaignId(campaignId)
                    .storeId(storeId)
                    .startDate(today)
                    .personalityAtStart(currentPersonality != null ? currentPersonality : "balanced")
                    .learningPeriodDays(learningDays)
                    .build();
            learningPeriodMapper.insert(entity);
            log.info("Learning period started for campaign={} with personality={}, duration={} days",
                    campaignId, currentPersonality, learningDays);
        }
    }

    @Override
    @Transactional
    public boolean checkAndRestartOnPersonalityChange(UUID campaignId, UUID storeId,
                                                      String currentPersonality) {
        if (campaignId == null) {
            return false;
        }

        CampaignLearningPeriodEntity entity = findByCampaignId(campaignId);
        if (entity == null) {
            // No learning period record — start one
            startOrRestart(campaignId, storeId, currentPersonality);
            return true;
        }

        String recordedPersonality = entity.getPersonalityAtStart();
        String effectiveCurrentPersonality = currentPersonality != null ? currentPersonality : "balanced";

        if (!effectiveCurrentPersonality.equals(recordedPersonality)) {
            // Personality changed — restart (Req 19.4)
            startOrRestart(campaignId, storeId, effectiveCurrentPersonality);
            log.info("Learning period restarted for campaign={}: personality changed from {} to {}",
                    campaignId, recordedPersonality, effectiveCurrentPersonality);
            return true;
        }

        return false;
    }

    @Override
    public BigDecimal clampBidForLearningPeriod(BigDecimal currentBid, BigDecimal proposedBid) {
        if (currentBid == null || proposedBid == null || currentBid.signum() <= 0) {
            return proposedBid;
        }

        BigDecimal maxChange = currentBid.multiply(LEARNING_PERIOD_MAX_BID_CHANGE_RATIO);
        BigDecimal actualChange = proposedBid.subtract(currentBid);

        if (actualChange.abs().compareTo(maxChange) <= 0) {
            // Change is within 10% — no further clamping needed
            return proposedBid;
        }

        // Clamp to 10% change in the same direction
        if (actualChange.signum() > 0) {
            return currentBid.add(maxChange).setScale(4, RoundingMode.HALF_UP);
        } else {
            return currentBid.subtract(maxChange).setScale(4, RoundingMode.HALF_UP);
        }
    }

    @Override
    public boolean shouldBlockBudgetChanges(UUID campaignId) {
        return isInLearningPeriod(campaignId);
    }

    @Override
    public List<LearningPeriodStatus> getStatuses(List<UUID> campaignIds) {
        if (campaignIds == null || campaignIds.isEmpty()) {
            return List.of();
        }

        List<LearningPeriodStatus> statuses = new ArrayList<>(campaignIds.size());
        for (UUID campaignId : campaignIds) {
            statuses.add(getStatus(campaignId));
        }
        return statuses;
    }

    // ─── Internal helpers ─────────────────────────────────────────────────────

    private CampaignLearningPeriodEntity findByCampaignId(UUID campaignId) {
        return learningPeriodMapper.selectOne(
                new LambdaQueryWrapper<CampaignLearningPeriodEntity>()
                        .eq(CampaignLearningPeriodEntity::getCampaignId, campaignId));
    }

    private LearningPeriodStatus computeStatus(CampaignLearningPeriodEntity entity) {
        LocalDate startDate = entity.getStartDate();
        int totalDays = entity.getLearningPeriodDays();
        LocalDate endDate = startDate.plusDays(totalDays);
        LocalDate today = LocalDate.now();

        if (today.isBefore(endDate)) {
            int daysRemaining = (int) ChronoUnit.DAYS.between(today, endDate);
            return LearningPeriodStatus.active(daysRemaining, startDate, totalDays,
                    entity.getPersonalityAtStart());
        }

        // Learning period has expired
        return LearningPeriodStatus.notInPeriod();
    }
}
