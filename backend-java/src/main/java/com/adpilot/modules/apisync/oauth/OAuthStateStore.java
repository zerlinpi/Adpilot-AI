package com.adpilot.modules.apisync.oauth;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Short-lived store for OAuth CSRF {@code state} values mapped to the
 * originating context ({@code storeId} + {@code region}). Used to validate the
 * callback came from a request we initiated (CSRF protection) and to recover
 * which store/region the authorization was for.
 *
 * <p>Prefers Redis (shared across instances) when available, transparently
 * falling back to an in-memory map with expiry when Redis is unreachable, so
 * the flow keeps working in single-node/dev setups. State is single-use:
 * {@link #consume(String)} removes it.</p>
 *
 * <p>SECURITY: the stored value contains no secrets — only the non-sensitive
 * storeId and region. The opaque state itself is high-entropy and random.</p>
 */
@Slf4j
@Component
public class OAuthStateStore {

    private static final String KEY_PREFIX = "amazon_ads:oauth_state:";

    private final RedisTemplate<String, Object> redisTemplate;
    private final Map<String, Entry> memory = new ConcurrentHashMap<>();

    public OAuthStateStore(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    /** Stored context for a state value. */
    public record StateData(String storeId, String region) {}

    private record Entry(String value, Instant expiresAt) {}

    /** Persist {@code state -> "storeId|region"} with a TTL. */
    public void save(String state, String storeId, String region, long ttlSeconds) {
        String value = encode(storeId, region);
        Duration ttl = Duration.ofSeconds(Math.max(ttlSeconds, 1));
        try {
            redisTemplate.opsForValue().set(KEY_PREFIX + state, value, ttl);
        } catch (Exception e) {
            log.warn("Redis unavailable for OAuth state; using in-memory fallback: {}", e.getMessage());
            memory.put(state, new Entry(value, Instant.now().plus(ttl)));
        }
    }

    /**
     * Atomically read and remove a state value. Returns {@code null} if the
     * state is unknown, already consumed, or expired.
     */
    public StateData consume(String state) {
        if (state == null || state.isBlank()) {
            return null;
        }
        String value = null;
        try {
            Object raw = redisTemplate.opsForValue().get(KEY_PREFIX + state);
            if (raw != null) {
                value = String.valueOf(raw);
                redisTemplate.delete(KEY_PREFIX + state);
            }
        } catch (Exception e) {
            log.warn("Redis unavailable for OAuth state lookup; using in-memory fallback: {}", e.getMessage());
        }
        if (value == null) {
            Entry entry = memory.remove(state);
            if (entry == null) {
                return null;
            }
            if (entry.expiresAt().isBefore(Instant.now())) {
                return null;
            }
            value = entry.value();
        }
        return decode(value);
    }

    private static String encode(String storeId, String region) {
        return (storeId == null ? "" : storeId) + "|" + (region == null ? "" : region);
    }

    private static StateData decode(String value) {
        if (value == null) {
            return null;
        }
        int sep = value.indexOf('|');
        if (sep < 0) {
            return new StateData(value, "na");
        }
        String storeId = value.substring(0, sep);
        String region = value.substring(sep + 1);
        return new StateData(storeId.isBlank() ? null : storeId, region.isBlank() ? "na" : region);
    }
}
