package com.adpilot.modules.advertising.support;

import lombok.Data;

import java.math.BigDecimal;

/**
 * Aggregated projection of {@code performance_daily} rolled up to a single
 * campaign. Column aliases map to these fields via MyBatis
 * map-underscore-to-camel-case.
 *
 * <p>{@code rowCount} is the number of contributing daily rows (0 when none
 * exist for the campaign). {@code allFinalized} is {@code 1} when every
 * contributing row carries {@code data_status = 'finalized'} and {@code 0} when
 * at least one row is still {@code preliminary}; it is used to derive the
 * faithful campaign-level {@code data_status} pass-through required by Req 2.3
 * (a single preliminary day keeps the whole campaign preliminary).</p>
 */
@Data
public class CampaignPerfAggRow {
    private String campaignId;
    private Integer rowCount;
    private BigDecimal spend;
    private Long clicks;
    private Long orders;
    private BigDecimal sales;
    private Integer allFinalized;
}
