package com.adpilot.modules.advertising.hosting;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Implementation of {@link BrandWordProtectionService} that checks negative-keyword
 * candidates against the store's brand word list with in-memory caching.
 *
 * <p>The cache uses a simple TTL-based eviction: brand word lists are loaded from the
 * database at most once per {@code adpilot.hosting.brand-word-cache-ttl-seconds}
 * (default 300 seconds / 5 minutes). This avoids repeated DB queries during a single
 * optimization run while keeping brand word additions reflected within a reasonable window.</p>
 *
 * <p>Match logic per Requirement 22.3:</p>
 * <ul>
 *   <li><b>exact</b>: {@code searchTerm.equalsIgnoreCase(brandWord)}</li>
 *   <li><b>contains</b>: {@code searchTerm.toLowerCase().contains(brandWord.toLowerCase())}</li>
 * </ul>
 *
 * <p>Validates: Requirements 5.4, 22.3, 22.4.</p>
 */
@Service
public class BrandWordProtectionServiceImpl implements BrandWordProtectionService {

    private static final Logger log = LoggerFactory.getLogger(BrandWordProtectionServiceImpl.class);

    private final BrandWordMapper brandWordMapper;
    private final long cacheTtlSeconds;

    /** In-memory cache: storeId → cached entry. */
    private final ConcurrentHashMap<UUID, CacheEntry> cache = new ConcurrentHashMap<>();

    public BrandWordProtectionServiceImpl(
            BrandWordMapper brandWordMapper,
            @Value("${adpilot.hosting.brand-word-cache-ttl-seconds:300}") long cacheTtlSeconds) {
        this.brandWordMapper = brandWordMapper;
        this.cacheTtlSeconds = cacheTtlSeconds;
    }

    @Override
    public BrandProtectionResult checkNegativeCandidate(UUID storeId, String searchTerm) {
        if (storeId == null || searchTerm == null || searchTerm.isBlank()) {
            return BrandProtectionResult.allowed();
        }

        List<BrandWordEntity> brandWords = loadBrandWords(storeId);
        if (brandWords.isEmpty()) {
            return BrandProtectionResult.allowed();
        }

        String termLower = searchTerm.toLowerCase(Locale.ROOT);

        for (BrandWordEntity bw : brandWords) {
            String wordLower = bw.getWord().toLowerCase(Locale.ROOT);
            String matchType = bw.getMatchType();

            if ("exact".equals(matchType)) {
                if (termLower.equals(wordLower)) {
                    log.info("Brand-word protection: rejected negative '{}' for store {} — exact match on '{}'",
                            searchTerm, storeId, bw.getWord());
                    return BrandProtectionResult.rejected(bw.getWord(), "exact");
                }
            } else if ("contains".equals(matchType)) {
                if (termLower.contains(wordLower)) {
                    log.info("Brand-word protection: rejected negative '{}' for store {} — contains match on '{}'",
                            searchTerm, storeId, bw.getWord());
                    return BrandProtectionResult.rejected(bw.getWord(), "contains");
                }
            }
        }

        return BrandProtectionResult.allowed();
    }

    @Override
    public void invalidateCache(UUID storeId) {
        if (storeId != null) {
            cache.remove(storeId);
            log.debug("Brand-word cache invalidated for store {}", storeId);
        }
    }

    /**
     * Load brand words for a store, using the in-memory cache when available and fresh.
     */
    private List<BrandWordEntity> loadBrandWords(UUID storeId) {
        CacheEntry entry = cache.get(storeId);
        Instant now = Instant.now();

        if (entry != null && !entry.isExpired(now, cacheTtlSeconds)) {
            return entry.words();
        }

        List<BrandWordEntity> words = brandWordMapper.selectByStoreId(storeId);
        cache.put(storeId, new CacheEntry(words, now));
        log.debug("Brand-word cache refreshed for store {} — {} words loaded", storeId, words.size());
        return words;
    }

    /**
     * Internal cache entry holding the brand word list and its load timestamp.
     */
    private record CacheEntry(List<BrandWordEntity> words, Instant loadedAt) {
        boolean isExpired(Instant now, long ttlSeconds) {
            return now.getEpochSecond() - loadedAt.getEpochSecond() > ttlSeconds;
        }
    }
}
