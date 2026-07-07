package com.adpilot.modules.advertising.service;

import com.adpilot.modules.advertising.entity.AdPlacementLockStrategyEntity;
import com.adpilot.modules.advertising.entity.AdPlacementLockTaskEntity;
import com.adpilot.modules.advertising.entity.BidChangeEntity;
import com.adpilot.modules.advertising.entity.KeywordEntity;
import com.adpilot.modules.advertising.mapper.AdPlacementLockStrategyMapper;
import com.adpilot.modules.advertising.mapper.AdPlacementLockTaskMapper;
import com.adpilot.modules.advertising.mapper.BidChangeMapper;
import com.adpilot.modules.advertising.mapper.KeywordMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Scheduled enforcement for Ad Placement Lock strategies (Req 26.3). On each tick
 * it loads every active strategy, then for each enabled keyword in the strategy's
 * campaign clamps the keyword bid into the configured {@code [bidMin, bidMax]}
 * range using the shared {@link HostingBidOptimizer#clampToRange} clamp (the same
 * clamp validated by Property 4). A bid that already lies inside the range is left
 * untouched; one that has drifted out is moved back to the nearest bound so the ad
 * holds its targeted placement.
 *
 * <p>Each enforcement run upserts a per-keyword {@code ad_placement_lock_tasks}
 * row recording the last bid applied and when it last ran. When a keyword bid is
 * changed, an auditable {@code bid_changes} row is written, mirroring
 * {@link AiHostingOptimizer}.
 *
 * <p>Per-strategy failures are isolated: an exception while enforcing one strategy
 * is logged and skipped so it never aborts the whole tick. Scheduling is enabled
 * application-wide; the cadence is configurable via
 * {@code adpilot.placement-lock.evaluate-ms} (default 5 min).
 */
@Slf4j
@Component
public class PlacementLockEvaluator {

    private static final String CHANGE_REASON = "placement_lock";
    private static final String ENTITY_TYPE_KEYWORD = "keyword";

    private final AdPlacementLockStrategyMapper strategyMapper;
    private final AdPlacementLockTaskMapper taskMapper;
    private final KeywordMapper keywordMapper;
    private final BidChangeMapper bidChangeMapper;

    public PlacementLockEvaluator(AdPlacementLockStrategyMapper strategyMapper,
                                  AdPlacementLockTaskMapper taskMapper,
                                  KeywordMapper keywordMapper,
                                  BidChangeMapper bidChangeMapper) {
        this.strategyMapper = strategyMapper;
        this.taskMapper = taskMapper;
        this.keywordMapper = keywordMapper;
        this.bidChangeMapper = bidChangeMapper;
    }

    /**
     * Scheduled entry point (Req 26.3). Fires on the configured fixed delay and
     * delegates to {@link #runOnce()}; never propagates an exception so the
     * scheduler keeps ticking.
     */
    @Scheduled(fixedDelayString = "${adpilot.placement-lock.evaluate-ms:300000}")
    public void evaluate() {
        try {
            EnforcementSummary summary = runOnce();
            if (summary.getStrategiesProcessed() > 0) {
                log.info("Placement lock enforcement tick: {} strategies, {} bids clamped, {} failed",
                        summary.getStrategiesProcessed(), summary.getBidsClamped(), summary.getStrategiesFailed());
            }
        } catch (Exception ex) {
            log.warn("Placement lock enforcement tick failed", ex);
        }
    }

    /**
     * Enforce every active strategy once and return an aggregate summary.
     * Per-strategy failures are isolated (logged and skipped).
     */
    public EnforcementSummary runOnce() {
        List<AdPlacementLockStrategyEntity> active = strategyMapper.selectList(
                new LambdaQueryWrapper<AdPlacementLockStrategyEntity>()
                        .eq(AdPlacementLockStrategyEntity::getStatus, "active"));

        EnforcementSummary summary = new EnforcementSummary();
        for (AdPlacementLockStrategyEntity strategy : active) {
            summary.strategiesProcessed++;
            try {
                summary.bidsClamped += enforceStrategy(strategy);
            } catch (Exception ex) {
                summary.strategiesFailed++;
                log.warn("Placement lock enforcement failed for strategy {}: {}",
                        strategy.getId(), rootMessage(ex));
            }
        }
        return summary;
    }

    /**
     * Enforce a single strategy: clamp every enabled keyword bid in the campaign
     * into {@code [bidMin, bidMax]} and upsert its enforcement task. Returns the
     * number of bids actually changed.
     */
    public int enforceStrategy(AdPlacementLockStrategyEntity strategy) {
        if (strategy == null || strategy.getBidMin() == null || strategy.getBidMax() == null) {
            return 0;
        }
        BigDecimal min = strategy.getBidMin();
        BigDecimal max = strategy.getBidMax();

        List<KeywordEntity> keywords = keywordMapper.selectList(
                new LambdaQueryWrapper<KeywordEntity>()
                        .eq(KeywordEntity::getCampaignId, strategy.getCampaignId())
                        .eq(KeywordEntity::getStatus, "enabled")
                        .isNotNull(KeywordEntity::getBid));

        int changed = 0;
        LocalDateTime now = LocalDateTime.now();
        for (KeywordEntity keyword : keywords) {
            BigDecimal current = keyword.getBid();
            if (current == null) {
                continue;
            }
            BigDecimal clamped = HostingBidOptimizer.clampToRange(current, min, max)
                    .setScale(4, RoundingMode.HALF_UP);

            if (clamped.compareTo(current) != 0) {
                keyword.setBid(clamped);
                keywordMapper.updateById(keyword);
                writeBidChange(strategy, keyword, current, clamped);
                changed++;
            }
            upsertTask(strategy, keyword, clamped, now);
        }
        return changed;
    }

    /** Insert or update the per-keyword enforcement task for this strategy. */
    private void upsertTask(AdPlacementLockStrategyEntity strategy, KeywordEntity keyword,
                            BigDecimal lastBid, LocalDateTime now) {
        AdPlacementLockTaskEntity existing = taskMapper.selectOne(
                new LambdaQueryWrapper<AdPlacementLockTaskEntity>()
                        .eq(AdPlacementLockTaskEntity::getStrategyId, strategy.getId())
                        .eq(AdPlacementLockTaskEntity::getKeywordId, keyword.getId())
                        .last("LIMIT 1"));
        if (existing == null) {
            taskMapper.insert(AdPlacementLockTaskEntity.builder()
                    .strategyId(strategy.getId())
                    .keywordId(keyword.getId())
                    .lastBid(lastBid)
                    .lastRunAt(now)
                    .build());
        } else {
            existing.setLastBid(lastBid);
            existing.setLastRunAt(now);
            taskMapper.updateById(existing);
        }
    }

    private void writeBidChange(AdPlacementLockStrategyEntity strategy, KeywordEntity keyword,
                                BigDecimal oldBid, BigDecimal newBid) {
        bidChangeMapper.insert(BidChangeEntity.builder()
                .storeId(strategy.getStoreId())
                .keywordId(keyword.getId())
                .campaignId(strategy.getCampaignId())
                .entityType(ENTITY_TYPE_KEYWORD)
                .oldBid(oldBid)
                .newBid(newBid)
                .changeReason(CHANGE_REASON)
                .isAutomated(true)
                .build());
    }

    private static String rootMessage(Throwable e) {
        Throwable cur = e;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String m = cur.getMessage();
        return m != null ? m : cur.getClass().getSimpleName();
    }

    /** Aggregate counters for one enforcement run. */
    @Getter
    public static final class EnforcementSummary {
        private int strategiesProcessed;
        private int strategiesFailed;
        private int bidsClamped;
    }
}
