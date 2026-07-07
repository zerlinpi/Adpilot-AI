package com.adpilot.modules.auth.service.impl;

import com.adpilot.modules.auth.service.ReconfirmationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Redis-backed {@link ReconfirmationService}. A confirmation marker is stored
 * keyed by user id with a short configurable TTL ({@code adpilot.security.reconfirm.window-seconds},
 * default 5 minutes), so the re-confirmation requirement is time-bounded.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReconfirmationServiceImpl implements ReconfirmationService {

    private static final String PREFIX = "auth:reconfirm:";
    private static final String CONFIRMED = "1";

    private final RedisTemplate<String, Object> redisTemplate;

    /** How long an identity re-confirmation stays valid for sensitive actions (seconds). */
    @Value("${adpilot.security.reconfirm.window-seconds:300}")
    private long windowSeconds;

    @Override
    public void confirm(String userId) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        redisTemplate.opsForValue().set(key(userId), CONFIRMED, Duration.ofSeconds(windowSeconds));
        log.debug("Recorded identity re-confirmation for user {}", userId);
    }

    @Override
    public boolean isConfirmed(String userId) {
        if (userId == null || userId.isBlank()) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(key(userId)));
        } catch (Exception e) {
            // Fail closed: if we cannot confirm a live marker, require re-confirmation.
            log.warn("Re-confirmation check failed for user {}: {}", userId, e.getMessage());
            return false;
        }
    }

    @Override
    public void clear(String userId) {
        if (userId == null || userId.isBlank()) {
            return;
        }
        try {
            redisTemplate.delete(key(userId));
        } catch (Exception e) {
            log.warn("Failed to clear re-confirmation marker for user {}: {}", userId, e.getMessage());
        }
    }

    private String key(String userId) {
        return PREFIX + userId;
    }
}
