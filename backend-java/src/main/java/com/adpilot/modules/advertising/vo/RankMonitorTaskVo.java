package com.adpilot.modules.advertising.vo;

import lombok.Builder;
import lombok.Data;

/**
 * Rank-monitor task row for the Rank Monitoring page (Req 28.2). Carries the
 * monitored keyword and its most recent organic rank (自然排名) and ad rank
 * (广告排名); ranks are {@code null} until a snapshot has been captured, in which
 * case the page renders a placeholder.
 */
@Data
@Builder
public class RankMonitorTaskVo {

    private String id;
    private String storeId;

    /** Store display name (店铺名称) so tasks can be grouped/managed per store. */
    private String storeName;

    private String productId;

    /** Product display name (商品名称); {@code null} when the task is not tied to a product. */
    private String productName;

    /** The monitored keyword (关键词). */
    private String keywordText;

    /** Task status: {@code active} | {@code paused}. */
    private String status;

    /** Latest organic rank (自然排名); {@code null} when not yet captured. */
    private Integer organicRank;

    /** Latest ad rank (广告排名); {@code null} when not yet captured. */
    private Integer adRank;

    /** Time the latest rank snapshot was captured; {@code null} when none. */
    private String lastCapturedAt;

    private String createdAt;
}
