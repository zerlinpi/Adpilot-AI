package com.adpilot.modules.finance.service.impl;

import com.adpilot.modules.finance.entity.ExchangeRateEntity;
import com.adpilot.modules.finance.mapper.ExchangeRateMapper;
import com.adpilot.modules.finance.service.ExchangeRateSource.RateLookup;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Wiring smoke test for {@link DatabaseExchangeRateSource} (Req 9.2.5): the
 * configured exchange-rate source is backed by the {@code exchange_rates} table
 * and returns, for a currency pair, the most-recent row whose
 * {@code effective_date} is on or before the requested date, narrowing to the
 * configured {@code source} when one is set.
 *
 * <p>Follows the project's mapper-backed unit-test convention (mock the mapper,
 * capture the query wrapper) — see {@code WatermarkStoreImplTest}.</p>
 */
@ExtendWith(MockitoExtension.class)
class DatabaseExchangeRateSourceTest {

    @Mock
    private ExchangeRateMapper exchangeRateMapper;

    @InjectMocks
    private DatabaseExchangeRateSource exchangeRateSource;

    private static final LocalDate REQUESTED = LocalDate.of(2024, 6, 15);

    /**
     * Registers the entity's table metadata so the lambda query wrapper can
     * resolve column names (normally done by MyBatis-Plus on startup). Mirrors
     * production, which maps camelCase fields to snake_case columns.
     */
    @BeforeAll
    static void initTableMetadata() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        TableInfoHelper.initTableInfo(assistant, ExchangeRateEntity.class);
    }

    private ExchangeRateEntity rate(String from, String to, BigDecimal value,
                                    LocalDate effective, String source) {
        return ExchangeRateEntity.builder()
                .baseCurrency(from)
                .quoteCurrency(to)
                .rate(value)
                .effectiveDate(effective)
                .source(source)
                .build();
    }

    /** Wraps a single row the way a {@code page(1,1)} ordered-desc query would. */
    private Page<ExchangeRateEntity> pageOf(ExchangeRateEntity... rows) {
        Page<ExchangeRateEntity> page = new Page<>(1, 1);
        page.setRecords(List.of(rows));
        return page;
    }

    @Test
    void findRateReturnsRateAndEffectiveDateOfMatchedRow() {
        // The DB returns, for page(1,1) ordered by effective_date desc, the most
        // recent row on or before the requested date (Req 9.2.1, 9.2.5).
        LocalDate effective = LocalDate.of(2024, 6, 10);
        ExchangeRateEntity row = rate("USD", "EUR", new BigDecimal("0.90000000"), effective, "ECB");
        when(exchangeRateMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                .thenReturn(pageOf(row));

        Optional<RateLookup> result = exchangeRateSource.findRate("USD", "EUR", REQUESTED);

        assertThat(result).isPresent();
        assertThat(result.get().rate()).isEqualByComparingTo("0.90000000");
        assertThat(result.get().effectiveDate()).isEqualTo(effective);
    }

    @Test
    void findRateRequestsOnlyTheSingleMostRecentRow() {
        // "Most recent" wiring: the source asks the table for a single-row page so
        // the DB ordering (effective_date desc) yields the latest applicable rate.
        when(exchangeRateMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                .thenReturn(pageOf(rate("USD", "EUR", new BigDecimal("0.9"), REQUESTED, null)));

        exchangeRateSource.findRate("USD", "EUR", REQUESTED);

        ArgumentCaptor<Page> pageCaptor = ArgumentCaptor.forClass(Page.class);
        verify(exchangeRateMapper).selectPage(pageCaptor.capture(), any(LambdaQueryWrapper.class));
        assertThat(pageCaptor.getValue().getSize()).isEqualTo(1L);
        assertThat(pageCaptor.getValue().getCurrent()).isEqualTo(1L);
    }

    @Test
    void findRateConstrainsPairAndEffectiveDateAndOrdersDescending() {
        when(exchangeRateMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                .thenReturn(pageOf(rate("USD", "EUR", new BigDecimal("0.9"), REQUESTED, null)));

        exchangeRateSource.findRate("USD", "EUR", REQUESTED);

        String sql = capturedSql();
        // Currency pair equality, effective_date upper bound, and descending order.
        assertThat(sql).contains("base_currency");
        assertThat(sql).contains("quote_currency");
        assertThat(sql).contains("effective_date <=");
        assertThat(sql).containsIgnoringCase("order by");
        assertThat(sql).containsIgnoringCase("desc");
    }

    @Test
    void findRateAppliesConfiguredSourceFilterWhenSet() {
        // Req 9.2.5: when a provider is configured, only rows from that source count.
        ReflectionTestUtils.setField(exchangeRateSource, "configuredSource", "ECB");
        when(exchangeRateMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                .thenReturn(pageOf(rate("USD", "EUR", new BigDecimal("0.9"), REQUESTED, "ECB")));

        exchangeRateSource.findRate("USD", "EUR", REQUESTED);

        assertThat(capturedSql()).contains("source");
    }

    @Test
    void findRateDoesNotFilterBySourceWhenBlank() {
        // Default (blank) configured source: any provider's rate is accepted.
        ReflectionTestUtils.setField(exchangeRateSource, "configuredSource", "");
        when(exchangeRateMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                .thenReturn(pageOf(rate("USD", "EUR", new BigDecimal("0.9"), REQUESTED, "ECB")));

        exchangeRateSource.findRate("USD", "EUR", REQUESTED);

        assertThat(capturedSql()).doesNotContain("source");
    }

    @Test
    void findRateReturnsEmptyWhenNoMatchingRow() {
        when(exchangeRateMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                .thenReturn(pageOf());

        assertThat(exchangeRateSource.findRate("USD", "JPY", REQUESTED)).isEmpty();
    }

    @Test
    void findRateReturnsEmptyForNullArgumentsWithoutQuerying() {
        assertThat(exchangeRateSource.findRate(null, "EUR", REQUESTED)).isEmpty();
        assertThat(exchangeRateSource.findRate("USD", null, REQUESTED)).isEmpty();
        assertThat(exchangeRateSource.findRate("USD", "EUR", null)).isEmpty();

        verifyNoInteractions(exchangeRateMapper);
    }

    /** Captures the query wrapper passed to the mapper and renders its SQL fragment. */
    private String capturedSql() {
        ArgumentCaptor<LambdaQueryWrapper> captor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(exchangeRateMapper).selectPage(any(Page.class), captor.capture());
        return captor.getValue().getTargetSql();
    }
}
