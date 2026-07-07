package com.adpilot.modules.advertising.mapper;

import com.adpilot.modules.advertising.entity.PerformanceDailyEntity;
import com.adpilot.modules.advertising.support.CampaignPerfAggRow;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface PerformanceDailyMapper extends BaseMapper<PerformanceDailyEntity> {

    /**
     * Roll up {@code performance_daily} to one row per campaign for the supplied
     * store and campaign ids, summing the headline metrics (spend, clicks,
     * orders, sales) required by Req 2.2 and deriving an {@code allFinalized}
     * flag that drives the faithful {@code data_status} pass-through of Req 2.3:
     * {@code MIN(data_status = 'finalized')} is {@code 1} only when every
     * contributing day is finalized, so any single {@code preliminary} day keeps
     * the campaign-level status {@code preliminary}.
     *
     * <p>Returns no row for a campaign that has no daily performance rows; the
     * caller maps that absence to a {@code null} {@code data_status}.</p>
     */
    @Select("<script>" +
            "SELECT campaign_id AS campaignId, COUNT(*) AS rowCount, " +
            "SUM(spend) AS spend, SUM(clicks) AS clicks, SUM(orders) AS orders, SUM(sales) AS sales, " +
            "MIN(CASE WHEN data_status = 'finalized' THEN 1 ELSE 0 END) AS allFinalized " +
            "FROM performance_daily " +
            "WHERE store_id = #{storeId} AND campaign_id IN " +
            "<foreach item='cid' collection='campaignIds' open='(' separator=',' close=')'>#{cid}</foreach> " +
            "GROUP BY campaign_id" +
            "</script>")
    List<CampaignPerfAggRow> aggregateByCampaign(@Param("storeId") String storeId,
                                                 @Param("campaignIds") List<String> campaignIds);
}
