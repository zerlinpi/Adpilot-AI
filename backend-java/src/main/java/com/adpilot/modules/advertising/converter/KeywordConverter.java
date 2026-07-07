package com.adpilot.modules.advertising.converter;

import com.adpilot.modules.advertising.entity.KeywordEntity;
import com.adpilot.modules.advertising.vo.KeywordVo;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;

public class KeywordConverter {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /**
     * The realized-CPC-to-bid ratio considered healthiest. Below this the bid is comfortably above
     * realized cost (room to win impressions); the further the realized ratio deviates from this band
     * the lower the resulting {@code bidHealthScore}.
     */
    private static final double IDEAL_CPC_TO_BID_RATIO = 0.65d;

    /** Neutral score used when there is no realized cost data to judge the bid against. */
    private static final int NEUTRAL_SCORE = 50;

    public static KeywordVo toVo(KeywordEntity entity) {
        if (entity == null) return null;
        return KeywordVo.builder()
                .id(entity.getId().toString())
                .campaignId(entity.getCampaignId().toString())
                .adGroupId(entity.getAdGroupId().toString())
                .storeId(entity.getStoreId().toString())
                .keywordText(entity.getKeywordText())
                .matchType(entity.getMatchType())
                .status(AdvertisingEnumEmitter.objectStatus(entity.getStatus()))
                .bid(entity.getBid() != null ? entity.getBid().doubleValue() : 0)
                .impressions(entity.getImpressions() != null ? entity.getImpressions() : 0)
                .clicks(entity.getClicks() != null ? entity.getClicks() : 0)
                .spend(entity.getSpend() != null ? entity.getSpend().doubleValue() : 0)
                .sales(entity.getSales() != null ? entity.getSales().doubleValue() : 0)
                .orders(entity.getOrders() != null ? entity.getOrders() : 0)
                .acos(entity.getAcos() != null ? entity.getAcos().doubleValue() : 0)
                .roas(entity.getRoas() != null ? entity.getRoas().doubleValue() : 0)
                .ctr(entity.getCtr() != null ? entity.getCtr().doubleValue() : 0)
                .cvr(entity.getCvr() != null ? entity.getCvr().doubleValue() : 0)
                .avgCpc(entity.getAvgCpc() != null ? entity.getAvgCpc().doubleValue() : 0)
                .bidHealthScore(computeBidHealthScore(entity))
                .externalId(entity.getExternalId())
                .createdAt(entity.getCreatedAt() != null ? entity.getCreatedAt().format(FORMATTER) : null)
                .updatedAt(entity.getUpdatedAt() != null ? entity.getUpdatedAt().format(FORMATTER) : null)
                .build();
    }

    /**
     * Compute the keyword's bid-health score in {@code [0, 100]} (Req 14.2) as a pure function of the
     * configured bid and the realized cost-per-click:
     * <ul>
     *   <li>no bid set ({@code bid <= 0}) → {@code 0} (cannot win, unhealthy);</li>
     *   <li>bid set but no realized CPC data ({@code avgCpc <= 0}) → {@link #NEUTRAL_SCORE};</li>
     *   <li>otherwise the score peaks at {@code 100} when the realized CPC sits at
     *       {@link #IDEAL_CPC_TO_BID_RATIO} of the bid and falls off linearly as the realized ratio
     *       deviates (over- or under-bidding), clamped to {@code [0, 100]}.</li>
     * </ul>
     */
    static int computeBidHealthScore(KeywordEntity entity) {
        double bid = toDouble(entity.getBid());
        if (bid <= 0) {
            return 0;
        }
        double cpc = toDouble(entity.getAvgCpc());
        if (cpc <= 0) {
            return NEUTRAL_SCORE;
        }
        double ratio = cpc / bid;
        double score = 100d - Math.abs(ratio - IDEAL_CPC_TO_BID_RATIO) * 100d;
        if (score < 0d) {
            return 0;
        }
        if (score > 100d) {
            return 100;
        }
        return (int) Math.round(score);
    }

    private static double toDouble(BigDecimal value) {
        return value != null ? value.doubleValue() : 0d;
    }
}
