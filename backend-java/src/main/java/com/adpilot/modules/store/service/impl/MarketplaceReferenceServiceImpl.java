package com.adpilot.modules.store.service.impl;

import com.adpilot.modules.store.entity.MarketplaceEntity;
import com.adpilot.modules.store.mapper.MarketplaceMapper;
import com.adpilot.modules.store.service.MarketplaceReferenceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * Default {@link MarketplaceReferenceService}. Loads the whole {@code marketplaces}
 * table once and caches the derived currency/timezone lookups in an immutable
 * {@link Snapshot} held by an {@link AtomicReference}. The snapshot is reloaded
 * only when it is older than {@code adpilot.reference.marketplace-cache-ttl-seconds}
 * (default 300s), so the near-static reference data is scanned at most once per
 * TTL window instead of once per request/tick (audit items L2 + M7).
 *
 * <p>The in-memory time-based cache is deliberately preferred over Redis: the
 * dataset is tiny and near-static, so this avoids serialization overhead while
 * still bounding staleness. Access is thread-safe — concurrent callers may
 * briefly race to reload, but each reload publishes a fresh immutable snapshot
 * and the last writer wins, which is harmless for idempotent reference data.</p>
 */
@Slf4j
@Service
public class MarketplaceReferenceServiceImpl implements MarketplaceReferenceService {

    private static final ZoneId UTC = ZoneId.of("UTC");

    private final MarketplaceMapper marketplaceMapper;
    private final long cacheTtlMillis;

    /**
     * Time source in epoch millis. Defaults to {@link System#currentTimeMillis()};
     * package-private so tests can advance simulated time to exercise TTL expiry
     * deterministically without sleeping.
     */
    private volatile LongSupplier clock = System::currentTimeMillis;

    /** The current cached snapshot, or {@code null} before the first load. */
    private final AtomicReference<Snapshot> snapshotRef = new AtomicReference<>();

    public MarketplaceReferenceServiceImpl(
            MarketplaceMapper marketplaceMapper,
            @Value("${adpilot.reference.marketplace-cache-ttl-seconds:300}") long cacheTtlSeconds) {
        this.marketplaceMapper = marketplaceMapper;
        this.cacheTtlMillis = Math.max(cacheTtlSeconds, 1L) * 1000L;
    }

    @Override
    public Map<UUID, String> currencyByMarketplaceId() {
        return currentSnapshot().currencyById;
    }

    @Override
    public Optional<String> currencyForMarketplace(UUID marketplaceId) {
        if (marketplaceId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(currentSnapshot().currencyById.get(marketplaceId));
    }

    @Override
    public ZoneId timezoneForMarketplace(UUID marketplaceId) {
        if (marketplaceId == null) {
            return UTC;
        }
        return currentSnapshot().zoneById.getOrDefault(marketplaceId, UTC);
    }

    /**
     * Return the cached snapshot, reloading it when absent or older than the TTL.
     * Fail-open: a load failure caches and returns an empty snapshot so callers
     * degrade to empty-map / UTC-fallback behavior rather than throwing.
     */
    private Snapshot currentSnapshot() {
        Snapshot current = snapshotRef.get();
        long now = clock.getAsLong();
        if (current != null && (now - current.loadedAt) < cacheTtlMillis) {
            return current;
        }
        Snapshot reloaded = load(now);
        snapshotRef.set(reloaded);
        return reloaded;
    }

    private Snapshot load(long now) {
        Map<UUID, String> currencyById = new HashMap<>();
        Map<UUID, ZoneId> zoneById = new HashMap<>();
        try {
            for (MarketplaceEntity m : safe(marketplaceMapper.selectList(null))) {
                if (m.getId() == null) {
                    continue;
                }
                if (StringUtils.hasText(m.getCurrency())) {
                    currencyById.put(m.getId(), normalizeCurrency(m.getCurrency()));
                }
                zoneById.put(m.getId(), parseZone(m.getTimezone()));
            }
        } catch (Exception e) {
            // Fail open: never let a reference-data query error break a caller.
            log.warn("Marketplace reference load failed; serving empty snapshot: {}", e.getMessage());
            return new Snapshot(Map.of(), Map.of(), now);
        }
        return new Snapshot(Map.copyOf(currencyById), Map.copyOf(zoneById), now);
    }

    /** Parse a marketplace timezone, mirroring the legacy blank/invalid -&gt; UTC handling. */
    private static ZoneId parseZone(String timezone) {
        if (!StringUtils.hasText(timezone)) {
            return UTC;
        }
        try {
            return ZoneId.of(timezone.trim());
        } catch (Exception e) {
            return UTC;
        }
    }

    /** Normalize an ISO currency code: trimmed and upper-cased (matches the consumers). */
    private static String normalizeCurrency(String code) {
        return code.trim().toUpperCase();
    }

    private static <T> java.util.List<T> safe(java.util.List<T> list) {
        return list != null ? list : java.util.List.of();
    }

    /** Package-private seam so tests can inject a deterministic time source. */
    void setClock(LongSupplier clock) {
        this.clock = clock != null ? clock : System::currentTimeMillis;
    }

    /** Immutable cached view of the marketplaces table plus the load timestamp. */
    private static final class Snapshot {
        final Map<UUID, String> currencyById;
        final Map<UUID, ZoneId> zoneById;
        final long loadedAt;

        Snapshot(Map<UUID, String> currencyById, Map<UUID, ZoneId> zoneById, long loadedAt) {
            this.currencyById = currencyById;
            this.zoneById = zoneById;
            this.loadedAt = loadedAt;
        }
    }
}
