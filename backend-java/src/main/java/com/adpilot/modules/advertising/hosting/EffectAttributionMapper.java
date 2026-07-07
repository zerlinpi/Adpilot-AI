package com.adpilot.modules.advertising.hosting;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * MyBatis-Plus mapper for the {@code effect_attributions} table (Req 8.3).
 *
 * <p>Persists per-metric attribution results after an Operation's measurement
 * window completes. Queries support the dashboard summary (aggregated savings),
 * decision explanation cards, and analytics endpoints.</p>
 */
@Mapper
public interface EffectAttributionMapper extends BaseMapper<EffectAttributionEntity> {
}
