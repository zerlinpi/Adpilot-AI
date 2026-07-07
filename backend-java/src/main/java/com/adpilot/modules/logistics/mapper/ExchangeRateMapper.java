package com.adpilot.modules.logistics.mapper;

import com.adpilot.modules.logistics.entity.ExchangeRateEntity;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.stereotype.Repository;

/**
 * Logistics exchange-rate mapper. Explicitly named to avoid a bean-name collision
 * with {@code com.adpilot.modules.finance.mapper.ExchangeRateMapper}, which
 * shares the same simple class name and would otherwise register under the same
 * default bean name ({@code exchangeRateMapper}).
 */
@Mapper
@Repository("logisticsExchangeRateMapper")
public interface ExchangeRateMapper extends BaseMapper<ExchangeRateEntity> {
}
