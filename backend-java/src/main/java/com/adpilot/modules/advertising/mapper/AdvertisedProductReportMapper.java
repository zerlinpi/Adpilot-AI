package com.adpilot.modules.advertising.mapper;

import com.adpilot.modules.advertising.entity.AdvertisedProductReportEntity;
import com.adpilot.modules.advertising.support.ProductAdAggRow;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AdvertisedProductReportMapper extends BaseMapper<AdvertisedProductReportEntity> {

    /**
     * Aggregate advertised-product report rows for a store by campaign / ad group
     * / SKU / ASIN, summing performance metrics. When {@code campaignName} is
     * provided the result is narrowed to that campaign; when {@code purchasedOnly}
     * is true only groups that produced at least one order are returned (the
     * basis for the 购买的其他商品 surface). Results are ordered by spend desc.
     */
    @Select("<script>" +
            "SELECT campaign_name AS campaignName, ad_group_name AS adGroupName, sku, asin, " +
            "SUM(impressions) AS impressions, SUM(clicks) AS clicks, " +
            "SUM(spend) AS spend, SUM(sales) AS sales, SUM(orders) AS orders " +
            "FROM raw_advertised_product_reports " +
            "WHERE store_id = #{storeId} " +
            "<if test='campaignName != null and campaignName != \"\"'> AND campaign_name = #{campaignName} </if>" +
            "GROUP BY campaign_name, ad_group_name, sku, asin " +
            "<if test='purchasedOnly'> HAVING SUM(orders) &gt; 0 </if>" +
            "ORDER BY SUM(spend) DESC " +
            "LIMIT #{limit}" +
            "</script>")
    List<ProductAdAggRow> aggregate(@Param("storeId") String storeId,
                                    @Param("campaignName") String campaignName,
                                    @Param("purchasedOnly") boolean purchasedOnly,
                                    @Param("limit") int limit);
}
