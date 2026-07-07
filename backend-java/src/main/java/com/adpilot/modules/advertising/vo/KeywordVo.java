package com.adpilot.modules.advertising.vo;

import lombok.Builder;
import lombok.Data;

/**
 * Canonical API schema for a Keyword (Req 14.1, 14.2).
 *
 * <p>Performance metrics are exposed in a single <strong>flattened</strong> shape (not nested under
 * an unread {@code performance} object) and the VO carries a {@code bidHealthScore} that the Frontend
 * reads directly. Enumerated fields ({@code matchType}, {@code status}) carry only
 * {@code Machine_Value_Enum} values per Req 14.7/48; the Frontend owns display translation.</p>
 */
@Data
@Builder
public class KeywordVo {

    private String id;
    private String campaignId;
    private String adGroupId;
    private String storeId;
    private String keywordText;
    private String matchType;
    private String status;
    private double bid;
    private long impressions;
    private int clicks;
    private double spend;
    private double sales;
    private int orders;
    private double acos;
    private double roas;
    private double ctr;
    private double cvr;
    private double avgCpc;

    /**
     * Health of the keyword's bid relative to its realized cost-per-click, in {@code [0, 100]}
     * (Req 14.2). A higher score means the configured {@code bid} sits in a healthy band above the
     * realized {@code avgCpc} (winning impressions without materially overpaying). Computed by
     * {@link com.adpilot.modules.advertising.converter.KeywordConverter}.
     */
    private int bidHealthScore;

    private String externalId;
    private String createdAt;
    private String updatedAt;
}
