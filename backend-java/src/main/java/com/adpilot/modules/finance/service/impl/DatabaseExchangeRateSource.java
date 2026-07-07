package com.adpilot.modules.finance.service.impl;

import com.adpilot.modules.finance.entity.ExchangeRateEntity;
import com.adpilot.modules.finance.mapper.ExchangeRateMapper;
import com.adpilot.modules.finance.service.ExchangeRateSource;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Default {@link ExchangeRateSource} backed by the {@code exchange_rates} table
 * (Req 9.2.5). The rate "effective on the date" is the row for the currency pair
 * with the greatest {@code effective_date} not later than the requested date
 * (Req 9.2.1).
 *
 * <p>The active provider can be narrowed with the
 * {@code adpilot.finance.exchange-rate.source} property: when set, only rows
 * whose {@code source} matches are considered; when blank, any source is
 * accepted.</p>
 */
@Component
@RequiredArgsConstructor
public class DatabaseExchangeRateSource implements ExchangeRateSource {

    private final ExchangeRateMapper exchangeRateMapper;

    /** Optional configured provider name; empty means "any source". */
    @Value("${adpilot.finance.exchange-rate.source:}")
    private String configuredSource;

    @Override
    public Optional<RateLookup> findRate(String fromCurrency, String toCurrency, LocalDate date) {
        if (fromCurrency == null || toCurrency == null || date == null) {
            return Optional.empty();
        }

        LambdaQueryWrapper<ExchangeRateEntity> query = new LambdaQueryWrapper<ExchangeRateEntity>()
                .eq(ExchangeRateEntity::getBaseCurrency, fromCurrency)
                .eq(ExchangeRateEntity::getQuoteCurrency, toCurrency)
                .le(ExchangeRateEntity::getEffectiveDate, date)
                .orderByDesc(ExchangeRateEntity::getEffectiveDate);

        if (StringUtils.hasText(configuredSource)) {
            query.eq(ExchangeRateEntity::getSource, configuredSource);
        }

        // Most recent rate effective on or before the requested date.
        IPage<ExchangeRateEntity> page = exchangeRateMapper.selectPage(new Page<>(1, 1), query);
        List<ExchangeRateEntity> rows = page.getRecords();
        if (rows.isEmpty()) {
            return Optional.empty();
        }

        ExchangeRateEntity row = rows.get(0);
        return Optional.of(new RateLookup(row.getRate(), row.getEffectiveDate()));
    }
}
