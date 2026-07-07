package com.adpilot.modules.auth.service.impl;

import com.adpilot.modules.auth.service.LoginSecurityService;
import com.adpilot.modules.user.entity.LoginLog;
import com.adpilot.modules.user.entity.User;
import com.adpilot.modules.user.mapper.LoginLogMapper;
import com.adpilot.modules.user.mapper.UserMapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Default implementation of {@link LoginSecurityService}.
 *
 * <p>The lockout threshold and duration are configurable via
 * {@code adpilot.security.lockout.*} properties. The counter and lock are
 * stored per account on the {@code users} row ({@code failed_login_count} and
 * {@code locked_until}), so they are inherently per-account (Req 11.1.6).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoginSecurityServiceImpl implements LoginSecurityService {

    private static final String STATUS_SUCCESS = "success";
    private static final String STATUS_FAILED = "failed";

    private final UserMapper userMapper;
    private final LoginLogMapper loginLogMapper;

    /** Number of consecutive failures that triggers a lockout. */
    @Value("${adpilot.security.lockout.max-failed-attempts:5}")
    private int maxFailedAttempts;

    /** How long, in minutes, an account stays locked once the limit is reached. */
    @Value("${adpilot.security.lockout.duration-minutes:15}")
    private long lockoutDurationMinutes;

    @Override
    public boolean isLocked(User user) {
        if (user == null || user.getLockedUntil() == null) {
            return false;
        }
        return user.getLockedUntil().isAfter(LocalDateTime.now());
    }

    @Override
    public void recordUnknownUser(String email, String ipAddress, String userAgent) {
        writeLog(null, null, email, ipAddress, userAgent, STATUS_FAILED, "User not found");
    }

    @Override
    public void recordLockedAttempt(User user, String email, String ipAddress, String userAgent) {
        String reason = "Account locked until " + user.getLockedUntil();
        writeLog(user.getId(), user.getOrgId(), email, ipAddress, userAgent, STATUS_FAILED, reason);
    }

    @Override
    public void recordFailedAttempt(User user, String email, String ipAddress, String userAgent, String reason) {
        LocalDateTime now = LocalDateTime.now();

        // If a previous lock has already expired, the consecutive run starts fresh.
        boolean priorLockExpired =
                user.getLockedUntil() != null && !user.getLockedUntil().isAfter(now);

        // Increment the counter atomically at the DB level so concurrent failed
        // logins cannot clobber each other's writes and undercount the run. When a
        // prior lock has expired the run restarts at 1; otherwise the stored value
        // is incremented in place via a single SQL expression.
        LambdaUpdateWrapper<User> countUpdate = new LambdaUpdateWrapper<User>()
                .eq(User::getId, user.getId());
        if (priorLockExpired) {
            countUpdate.setSql("failed_login_count = 1");
        } else {
            countUpdate.setSql("failed_login_count = failed_login_count + 1");
        }
        userMapper.update(null, countUpdate);

        // Re-read the atomically-updated counter so the "just crossed the threshold"
        // decision is based on the true post-increment value rather than a possibly
        // stale in-memory value.
        int newCount = resolvePostIncrementCount(user, priorLockExpired);

        // Persist the resulting lock state: set locked_until when the threshold is
        // reached, otherwise clear any expired lock (preserving prior semantics).
        String logReason = reason;
        LocalDateTime newLockedUntil;
        if (newCount >= maxFailedAttempts) {
            newLockedUntil = now.plusMinutes(lockoutDurationMinutes);
            logReason = reason + " (account locked after " + newCount + " failed attempts)";
            log.warn("Account {} locked until {} after {} consecutive failed attempts",
                    user.getEmail(), newLockedUntil, newCount);
        } else {
            newLockedUntil = null;
        }

        LambdaUpdateWrapper<User> lockUpdate = new LambdaUpdateWrapper<User>()
                .eq(User::getId, user.getId())
                .set(User::getLockedUntil, newLockedUntil);
        userMapper.update(null, lockUpdate);

        user.setFailedLoginCount(newCount);
        user.setLockedUntil(newLockedUntil);

        writeLog(user.getId(), user.getOrgId(), email, ipAddress, userAgent, STATUS_FAILED, logReason);
    }

    /**
     * Read back the counter after the atomic increment so the lockout decision
     * reflects the authoritative DB value. Falls back to the locally-computed value
     * only when the row cannot be re-read (e.g. it was removed concurrently).
     */
    private int resolvePostIncrementCount(User user, boolean priorLockExpired) {
        User refreshed = userMapper.selectById(user.getId());
        if (refreshed != null && refreshed.getFailedLoginCount() != null) {
            return refreshed.getFailedLoginCount();
        }
        int current = priorLockExpired || user.getFailedLoginCount() == null
                ? 0
                : user.getFailedLoginCount();
        return current + 1;
    }

    @Override
    public void recordRejectedAttempt(User user, String email, String ipAddress, String userAgent, String reason) {
        writeLog(user.getId(), user.getOrgId(), email, ipAddress, userAgent, STATUS_FAILED, reason);
    }

    @Override
    public void recordSuccessfulLogin(User user, String email, String ipAddress, String userAgent) {
        LocalDateTime now = LocalDateTime.now();
        LambdaUpdateWrapper<User> update = new LambdaUpdateWrapper<User>()
                .eq(User::getId, user.getId())
                .set(User::getFailedLoginCount, 0)
                .set(User::getLockedUntil, null)
                .set(User::getLastLoginAt, now);
        user.setFailedLoginCount(0);
        user.setLockedUntil(null);
        user.setLastLoginAt(now);
        userMapper.update(null, update);

        writeLog(user.getId(), user.getOrgId(), email, ipAddress, userAgent, STATUS_SUCCESS, null);
    }

    /**
     * Persist a login-log row. Failures to log must never block authentication,
     * so any exception is swallowed with a warning.
     */
    private void writeLog(UUID userId, UUID orgId, String username, String ip, String ua,
                          String status, String failureReason) {
        try {
            LoginLog entry = LoginLog.builder()
                    .id(UUID.randomUUID())
                    .userId(userId)
                    .orgId(orgId)
                    .username(username)
                    .email(username)
                    .ipAddress(ip)
                    .userAgent(ua)
                    .loginStatus(status)
                    .failureReason(failureReason)
                    .build();
            loginLogMapper.insert(entry);
        } catch (Exception e) {
            log.warn("Failed to record login log: {}", e.getMessage());
        }
    }
}
