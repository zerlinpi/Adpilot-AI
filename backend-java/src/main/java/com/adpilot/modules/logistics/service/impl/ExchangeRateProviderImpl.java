package com.adpilot.modules.logistics.service.impl;

import com.adpilot.modules.logistics.entity.ExchangeRateEntity;
import com.adpilot.modules.logistics.mapper.ExchangeRateMapper;
import com.adpilot.modules.logistics.service.ExchangeRateProvider;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Default {@link ExchangeRateProvider} backed by the documented
 * {@code exchange_rates} table. Resolves the most recent documented rate for a
 * currency pair effective on or before the requested date. This reads only
 * documented in-repository rates — it never reaches out to an external FX
 * service.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExchangeRateProviderImpl implements ExchangeRateProvider {

    private final ExchangeRateMapper exchangeRateMapper;

    @Override
    public Optional<BigDecimal> findRate(String baseCurrency, String quoteCurrency, LocalDate effectiveDate) {
        if (isBlank(baseCurrency) || isBlank(quoteCurrency)) {
            return Optional.empty();
        }

        LambdaQueryWrapper<ExchangeRateEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ExchangeRateEntity::getBaseCurrency, baseCurrency);
        wrapper.eq(ExchangeRateEntity::getQuoteCurrency, quoteCurrency);
        if (effectiveDate != null) {
            wrapper.le(ExchangeRateEntity::getEffectiveDate, effectiveDate);
        }
        wrapper.orderByDesc(ExchangeRateEntity::getEffectiveDate);
        wrapper.last("LIMIT 1");

        ExchangeRateEntity rate = exchangeRateMapper.selectOne(wrapper);
        if (rate == null || rate.getRate() == null) {
            return Optional.empty();
        }
        return Optional.of(rate.getRate());
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
