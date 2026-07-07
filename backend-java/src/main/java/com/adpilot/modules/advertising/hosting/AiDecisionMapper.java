package com.adpilot.modules.advertising.hosting;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/**
 * MyBatis-Plus mapper for the {@code ai_decisions} table (Req 37.1).
 *
 * <p>Persists every AI decision regardless of Execution_Mode. Queries support
 * the decision list API, dashboard summary, and analytics endpoints.</p>
 */
@Mapper
public interface AiDecisionMapper extends BaseMapper<AiDecisionEntity> {
}
