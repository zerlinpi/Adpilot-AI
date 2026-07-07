package com.adpilot.modules.store.service.impl;

import com.adpilot.modules.store.entity.MarketplaceEntity;
import com.adpilot.modules.store.mapper.MarketplaceMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MarketplaceReferenceServiceImpl} — the shared, cached
 * reference-data accessor introduced to remove the per-request full-table scan
 * of the near-static {@code marketplaces} table (audit items L2 + M7).
 *
 * <p>Verifies:
 * <ul>
 *   <li>the currency map is built from the mapper rows and normalized (trimmed,
 *       upper-cased), skipping rows without a currency;</li>
 *   <li>the underlying mapper is queried only ONCE across many reads inside the
 *       TTL window (caching), and reloaded exactly once more after the TTL
 *       expires — exercised deterministically through the package-private time
 *       seam ({@link MarketplaceReferenceServiceImpl#setClock}) rather than by
 *       sleeping;</li>
 *   <li>timezone resolution falls back to {@code UTC} for a null / unknown id and
 *       for a blank or invalid configured timezone, while a valid zone is
 *       returned as-is.</li>
 * </ul>
 */
@DisplayName("MarketplaceReferenceServiceImpl — cached, normalized marketplace reference data")
class MarketplaceReferenceServiceImplTest {

    private static final long TTL_SECONDS = 300L;

    private MarketplaceMapper marketplaceMapper;
    private MarketplaceReferenceServiceImpl service;
    private AtomicLong now;

    private final UUID usId = UUID.randomUUID();
    private final UUID deId = UUID.randomUUID();
    private final UUID blankTzId = UUID.randomUUID();
    private final UUID invalidTzId = UUID.randomUUID();
    private final UUID noCurrencyId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        marketplaceMapper = mock(MarketplaceMapper.class);
        service = new MarketplaceReferenceServiceImpl(marketplaceMapper, TTL_SECONDS);

        // Deterministic, controllable clock (epoch millis) via the package-private seam.
        now = new AtomicLong(1_000_000L);
        service.setClock(now::get);

        // Rows exercise: lower-case currency, whitespace-padded currency, a valid
        // zone, a blank zone, an invalid zone, a null id (skipped) and a row with
        // no currency (present for timezone, absent from the currency map).
        when(marketplaceMapper.selectList(any())).thenReturn(List.of(
                MarketplaceEntity.builder().id(usId).code("US").currency("usd")
                        .timezone("America/New_York").build(),
                MarketplaceEntity.builder().id(deId).code("DE").currency("  eur ")
                        .timezone("Europe/Berlin").build(),
                MarketplaceEntity.builder().id(blankTzId).code("BL").currency("GBP")
                        .timezone("   ").build(),
                MarketplaceEntity.builder().id(invalidTzId).code("IV").currency("JPY")
                        .timezone("Not/ARealZone").build(),
                MarketplaceEntity.builder().id(noCurrencyId).code("NC").currency(null)
                        .timezone("Asia/Tokyo").build(),
                MarketplaceEntity.builder().id(null).code("XX").currency("CAD")
                        .timezone("UTC").build()));
    }

    @Test
    @DisplayName("currency map is built from mapper rows and normalized (trimmed, upper-cased)")
    void currencyMapIsNormalized() {
        Map<UUID, String> byId = service.currencyByMarketplaceId();

        assertThat(byId).containsEntry(usId, "USD");   // lower-case -> upper
        assertThat(byId).containsEntry(deId, "EUR");   // trimmed + upper
        assertThat(byId).containsEntry(blankTzId, "GBP");
        assertThat(byId).containsEntry(invalidTzId, "JPY");
        // Row with no currency is excluded from the currency map.
        assertThat(byId).doesNotContainKey(noCurrencyId);
        // Row with a null id is skipped entirely.
        assertThat(byId).doesNotContainValue("CAD");

        assertThat(service.currencyForMarketplace(usId)).contains("USD");
        assertThat(service.currencyForMarketplace(noCurrencyId)).isEmpty();
        assertThat(service.currencyForMarketplace(null)).isEmpty();
        assertThat(service.currencyForMarketplace(UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("mapper is queried only once across many reads within the TTL window")
    void cachesWithinTtl() {
        // Many reads of different derived lookups, all inside the TTL window.
        service.currencyByMarketplaceId();
        service.currencyForMarketplace(usId);
        service.timezoneForMarketplace(deId);
        now.addAndGet((TTL_SECONDS - 1) * 1000L); // still inside the TTL
        service.currencyByMarketplaceId();
        service.timezoneForMarketplace(usId);

        verify(marketplaceMapper, times(1)).selectList(any());
    }

    @Test
    @DisplayName("mapper is reloaded exactly once more after the TTL expires")
    void reloadsAfterTtlExpiry() {
        service.currencyByMarketplaceId();          // load #1
        verify(marketplaceMapper, times(1)).selectList(any());

        // Advance time past the TTL: the next read must trigger a single reload.
        now.addAndGet(TTL_SECONDS * 1000L + 1L);
        service.currencyByMarketplaceId();          // load #2
        service.timezoneForMarketplace(usId);       // still cached (no extra load)

        verify(marketplaceMapper, times(2)).selectList(any());
    }

    @Test
    @DisplayName("timezone resolves valid zones and falls back to UTC on null/blank/invalid/unknown")
    void timezoneFallbacks() {
        ZoneId utc = ZoneId.of("UTC");

        // Valid configured zones are returned as-is.
        assertThat(service.timezoneForMarketplace(usId)).isEqualTo(ZoneId.of("America/New_York"));
        assertThat(service.timezoneForMarketplace(deId)).isEqualTo(ZoneId.of("Europe/Berlin"));

        // Blank / invalid configured zones fall back to UTC.
        assertThat(service.timezoneForMarketplace(blankTzId)).isEqualTo(utc);
        assertThat(service.timezoneForMarketplace(invalidTzId)).isEqualTo(utc);

        // Null id and unknown id fall back to UTC.
        assertThat(service.timezoneForMarketplace(null)).isEqualTo(utc);
        assertThat(service.timezoneForMarketplace(UUID.randomUUID())).isEqualTo(utc);
    }

    @Test
    @DisplayName("a mapper failure fails open: empty currency map and UTC timezone fallback")
    void failsOpenOnMapperError() {
        when(marketplaceMapper.selectList(any())).thenThrow(new RuntimeException("db down"));
        // Force a reload past the previously cached snapshot.
        now.addAndGet(TTL_SECONDS * 1000L + 1L);

        assertThat(service.currencyByMarketplaceId()).isEmpty();
        assertThat(service.currencyForMarketplace(usId)).isEmpty();
        assertThat(service.timezoneForMarketplace(usId)).isEqualTo(ZoneId.of("UTC"));
    }
}
