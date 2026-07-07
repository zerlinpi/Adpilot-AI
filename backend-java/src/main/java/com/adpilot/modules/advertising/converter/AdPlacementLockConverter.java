package com.adpilot.modules.advertising.converter;

import com.adpilot.modules.advertising.entity.AdPlacementLockStrategyEntity;
import com.adpilot.modules.advertising.entity.AdPlacementLockTaskEntity;
import com.adpilot.modules.advertising.vo.AdPlacementLockStrategyVo;
import com.adpilot.modules.advertising.vo.AdPlacementLockTaskVo;

import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;

/**
 * Maps Ad Placement Lock entities to their view objects (Req 26.1, 26.2),
 * resolving campaign names and keyword texts from caller-supplied lookup maps.
 */
public final class AdPlacementLockConverter {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private AdPlacementLockConverter() {
    }

    public static AdPlacementLockStrategyVo toVo(AdPlacementLockStrategyEntity entity,
                                                 Map<UUID, String> campaignNames) {
        if (entity == null) {
            return null;
        }
        return AdPlacementLockStrategyVo.builder()
                .id(idStr(entity.getId()))
                .storeId(idStr(entity.getStoreId()))
                .campaignId(idStr(entity.getCampaignId()))
                .campaignName(campaignNames == null ? null : campaignNames.get(entity.getCampaignId()))
                .targetPlacement(entity.getTargetPlacement())
                .bidMin(toDouble(entity.getBidMin()))
                .bidMax(toDouble(entity.getBidMax()))
                .status(entity.getStatus())
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().format(FORMATTER) : null)
                .build();
    }

    public static AdPlacementLockTaskVo toVo(AdPlacementLockTaskEntity task,
                                             AdPlacementLockStrategyEntity strategy,
                                             Map<UUID, String> campaignNames,
                                             Map<UUID, String> keywordTexts) {
        if (task == null) {
            return null;
        }
        return AdPlacementLockTaskVo.builder()
                .id(idStr(task.getId()))
                .strategyId(idStr(task.getStrategyId()))
                .targetPlacement(strategy != null ? strategy.getTargetPlacement() : null)
                .campaignName(strategy != null && campaignNames != null
                        ? campaignNames.get(strategy.getCampaignId()) : null)
                .keywordId(idStr(task.getKeywordId()))
                .keywordText(keywordTexts == null ? null : keywordTexts.get(task.getKeywordId()))
                .lastBid(toDouble(task.getLastBid()))
                .lastRunAt(task.getLastRunAt() != null ? task.getLastRunAt().format(FORMATTER) : null)
                .createdAt(task.getCreatedAt() != null ? task.getCreatedAt().format(FORMATTER) : null)
                .build();
    }

    private static String idStr(UUID id) {
        return id != null ? id.toString() : null;
    }

    private static Double toDouble(java.math.BigDecimal value) {
        return value != null ? value.doubleValue() : null;
    }
}
