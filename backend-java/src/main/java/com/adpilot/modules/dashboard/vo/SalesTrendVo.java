package com.adpilot.modules.dashboard.vo;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Sales trend chart payload (Req 18.2): the selected {@link #granularity}
 * (day/week/month) and the ordered list of {@link #points}, each carrying the
 * dual-axis ad-spend and ad-sales series.
 */
@Data
@Builder
public class SalesTrendVo {

    /** {@code day}, {@code week}, or {@code month}. */
    private String granularity;

    /** Reporting currency the series amounts are expressed in. */
    private String currency;

    /** Ordered trend buckets. */
    @Builder.Default
    private List<TrendPointVo> points = new ArrayList<>();
}
