package com.adpilot.modules.auth.service.impl;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.common.security.JwtUtil;
import com.adpilot.common.security.SessionControlService;
import com.adpilot.modules.auth.dto.DepartmentInfo;
import com.adpilot.modules.auth.dto.LoginRequest;
import com.adpilot.modules.auth.dto.LoginResponse;
import com.adpilot.modules.auth.dto.UserInfo;
import com.adpilot.modules.auth.service.AuthService;
import com.adpilot.modules.auth.service.LoginRateLimiter;
import com.adpilot.modules.auth.service.LoginSecurityService;
import com.adpilot.modules.auth.service.ReconfirmationService;
import com.adpilot.modules.auth.service.TwoFactorService;
import com.adpilot.modules.audit.service.AuditLogService;
import com.adpilot.common.utils.SecurityUtils;
import com.adpilot.modules.user.entity.Department;
import com.adpilot.modules.user.entity.LoginLog;
import com.adpilot.modules.user.entity.User;
import com.adpilot.modules.user.entity.UserDepartment;
import com.adpilot.modules.user.mapper.DepartmentMapper;
import com.adpilot.modules.user.mapper.LoginLogMapper;
import com.adpilot.modules.user.mapper.UserDepartmentMapper;
import com.adpilot.modules.user.service.UserService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserService userService;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final LoginSecurityService loginSecurityService;
    private final LoginRateLimiter loginRateLimiter;
    private final TwoFactorService twoFactorService;
    private final ReconfirmationService reconfirmationService;
    private final SessionControlService sessionControlService;
    private final UserDepartmentMapper userDepartmentMapper;
    private final DepartmentMapper departmentMapper;
    private final LoginLogMapper loginLogMapper;
    private final AuditLogService auditLogService;

    /**
     * Constant, syntactically-valid bcrypt hash used only as a decoy for the
     * unknown-user / missing-hash branches. Running {@code passwordEncoder.matches}
     * against this hash makes those branches spend comparable CPU time to the
     * known-user branch, so an attacker cannot distinguish a registered email from
     * an unregistered one by measuring response latency (login user-enumeration via
     * timing). The result is intentionally ignored — it never authenticates anyone.
     * It is a throwaway hash and corresponds to no real account password.
     */
    private static final String DUMMY_BCRYPT_HASH =
            "$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    @Value("${adpilot.jwt.expiration:86400000}")
    private Long jwtExpiration;

    @Override
    public LoginResponse login(LoginRequest request, String ipAddress, String userAgent) {
        // IP-based brute-force / enumeration throttle (audit L3): block further
        // attempts from a client IP that has exceeded the failed-attempt threshold
        // within the rolling window, BEFORE any credential check. Runs after basic
        // input validation (performed by the controller). Fails open on infra errors
        // and keeps the message generic so it reveals nothing about any account.
        if (loginRateLimiter.isBlocked(ipAddress)) {
            throw new BusinessException(429, "TOO_MANY_REQUESTS",
                    "Too many login attempts. Please try again later.");
        }

        User user = userService.getUserByEmail(request.getEmail()).orElse(null);

        if (user == null) {
            // Perform a dummy bcrypt comparison so the unknown-user branch spends
            // comparable time to the known-user branch, preventing timing-based
            // account enumeration. The result is deliberately discarded.
            passwordEncoder.matches(request.getPassword(), DUMMY_BCRYPT_HASH);
            loginRateLimiter.recordFailure(ipAddress);
            loginSecurityService.recordUnknownUser(request.getEmail(), ipAddress, userAgent);
            throw new BusinessException(401, "INVALID_CREDENTIALS", "Invalid email or password");
        }

        // Reject login attempts while the account is locked (Req 11.1.2).
        if (loginSecurityService.isLocked(user)) {
            loginSecurityService.recordLockedAttempt(user, request.getEmail(), ipAddress, userAgent);
            throw new BusinessException(401, "ACCOUNT_LOCKED",
                    "Account is locked due to too many failed login attempts. Please try again later.");
        }

        if (!verifyPassword(request.getPassword(), user.getPasswordHash())) {
            // Increment the per-account counter and lock if the limit is reached (Req 11.1.1, 11.1.6).
            loginRateLimiter.recordFailure(ipAddress);
            loginSecurityService.recordFailedAttempt(user, request.getEmail(), ipAddress, userAgent, "Wrong password");
            throw new BusinessException(401, "INVALID_CREDENTIALS", "Invalid email or password");
        }

        if (!"active".equals(user.getStatus())) {
            loginSecurityService.recordRejectedAttempt(user, request.getEmail(), ipAddress, userAgent, "Account disabled");
            throw new BusinessException(403, "ACCOUNT_DISABLED", "Account is not active");
        }

        // Require a valid second factor before completing login when 2FA is
        // enabled for the account (Req 11.2.2).
        if (twoFactorService.isEnabled(user)) {
            if (!twoFactorService.verifyCode(user, request.getTwoFactorCode())) {
                loginRateLimiter.recordFailure(ipAddress);
                loginSecurityService.recordRejectedAttempt(user, request.getEmail(), ipAddress, userAgent,
                        "Invalid or missing two-factor code");
                throw new BusinessException(401, "TWO_FACTOR_REQUIRED",
                        "A valid two-factor authentication code is required");
            }
        }

        String primaryRole = userService.getUserPrimaryRole(user.getId().toString());
        List<String> permissions = userService.getUserPermissions(user.getId().toString());
        String token = jwtUtil.generateToken(
                user.getId().toString(),
                user.getEmail(),
                primaryRole != null ? List.of(primaryRole) : List.of());

        // Reset the failed-attempt counter and record a success login log (Req 11.1.5, 11.1.4).
        loginSecurityService.recordSuccessfulLogin(user,
                user.getName() != null ? user.getName() : user.getEmail(), ipAddress, userAgent);
        // A successful login clears the per-IP failure counter (audit L3): legitimate
        // users' occasional mistakes must not accumulate toward a block.
        loginRateLimiter.reset(ipAddress);

        UserInfo userInfo = UserInfo.builder()
                .id(user.getId().toString())
                .email(user.getEmail())
                .name(user.getName())
                .avatarUrl(user.getAvatarUrl())
                .role(primaryRole)
                .permissions(permissions)
                .department(getDepartmentInfo(user.getId().toString()))
                .defaultStoreId(user.getDefaultStoreId() != null ? user.getDefaultStoreId().toString() : null)
                .build();

        log.info("User logged in successfully: {}", user.getEmail());

        return LoginResponse.builder()
                .token(token)
                .tokenType("Bearer")
                .expiresIn(jwtExpiration / 1000)
                .user(userInfo)
                .build();
    }

    @Override
    public UserInfo getCurrentUser(String userId) {
        User user = userService.getUserById(userId)
                .orElseThrow(() -> new BusinessException(404, "USER_NOT_FOUND", "User not found"));

        List<String> permissions = userService.getUserPermissions(userId);
        String primaryRole = userService.getUserPrimaryRole(userId);

        return UserInfo.builder()
                .id(user.getId().toString())
                .email(user.getEmail())
                .name(user.getName())
                .avatarUrl(user.getAvatarUrl())
                .role(primaryRole)
                .permissions(permissions)
                .department(getDepartmentInfo(userId))
                .defaultStoreId(user.getDefaultStoreId() != null ? user.getDefaultStoreId().toString() : null)
                .build();
    }

    @Override
    public void reconfirmIdentity(String userId, String password, String twoFactorCode) {
        User user = userService.getUserById(userId)
                .orElseThrow(() -> new BusinessException(404, "USER_NOT_FOUND", "User not found"));

        if (password == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new BusinessException(401, "RECONFIRMATION_FAILED", "Identity re-confirmation failed");
        }

        // When 2FA is enabled, the second factor is part of re-confirmation.
        if (twoFactorService.isEnabled(user) && !twoFactorService.verifyCode(user, twoFactorCode)) {
            throw new BusinessException(401, "RECONFIRMATION_FAILED", "Identity re-confirmation failed");
        }

        reconfirmationService.confirm(userId);
        log.info("Identity re-confirmed for user {}", userId);

        writeAudit("IDENTITY_RECONFIRM", parseUuidOrNull(userId));
    }

    @Override
    public void changePassword(String userId, String currentPassword, String newPassword) {
        User user = userService.getUserById(userId)
                .orElseThrow(() -> new BusinessException(404, "USER_NOT_FOUND", "User not found"));

        if (currentPassword == null || !passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            throw new BusinessException(401, "INVALID_CREDENTIALS", "Current password is incorrect");
        }
        if (newPassword == null || passwordEncoder.matches(newPassword, user.getPasswordHash())) {
            throw new BusinessException(409, "PASSWORD_UNCHANGED",
                    "New password must be different from the current password");
        }

        User update = new User();
        update.setPasswordHash(newPassword);
        userService.updateUser(userId, update);
        sessionControlService.invalidateUserSessions(userId);
        reconfirmationService.clear(userId);
        log.info("Password changed and sessions invalidated for user {}", userId);

        writeAudit("PASSWORD_CHANGE", parseUuidOrNull(userId));
    }

    @Override
    public void invalidateUserSessions(String userId) {
        sessionControlService.invalidateUserSessions(userId);
        // Also drop any open re-confirmation window for the terminated user.
        reconfirmationService.clear(userId);

        writeAudit("SESSION_INVALIDATE", parseUuidOrNull(userId));
    }

    /**
     * Verify a raw password against a stored bcrypt hash. When the stored hash is
     * absent or blank (an ineligible account) we still run a dummy bcrypt compare
     * so the timing matches the normal wrong-password path, then report failure —
     * rather than short-circuiting and leaking that this account has no usable
     * password via a faster response.
     */
    private boolean verifyPassword(String rawPassword, String storedHash) {
        if (storedHash == null || storedHash.isBlank()) {
            passwordEncoder.matches(rawPassword, DUMMY_BCRYPT_HASH);
            return false;
        }
        return passwordEncoder.matches(rawPassword, storedHash);
    }

    /**
     * Write a forensic AUDIT-TRAIL entry for a sensitive authentication action (password
     * change, identity re-confirmation, session invalidation). Auditing is additive and
     * best-effort: a failure here is logged and swallowed so it can never break the business
     * operation. No credential values are ever recorded — the details payload is empty.
     */
    private void writeAudit(String action, UUID entityId) {
        try {
            auditLogService.createLog(resolveActorId(), resolveOrgId(), action, "user", entityId, Map.of());
        } catch (Exception ex) {
            log.warn("Failed to write audit log action={} entityId={}: {}", action, entityId, ex.getMessage());
        }
    }

    /** Acting user id from the security context, or {@code null} for a system/background actor. */
    private static UUID resolveActorId() {
        return parseUuidOrNull(SecurityUtils.getCurrentUserIdOrNull());
    }

    /** Caller org id from the security context, or {@code null} when unauthenticated. */
    private static UUID resolveOrgId() {
        if (!SecurityUtils.isAuthenticated()) {
            return null;
        }
        return parseUuidOrNull(SecurityUtils.getCurrentOrgId());
    }

    private static UUID parseUuidOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private DepartmentInfo getDepartmentInfo(String userId) {
        try {
            LambdaQueryWrapper<UserDepartment> w = new LambdaQueryWrapper<>();
            w.eq(UserDepartment::getUserId, UUID.fromString(userId)).last("LIMIT 1");
            UserDepartment ud = userDepartmentMapper.selectOne(w);
            if (ud == null || ud.getDepartmentId() == null) {
                return null;
            }
            Department dept = departmentMapper.selectById(ud.getDepartmentId());
            if (dept == null) {
                return null;
            }
            return DepartmentInfo.builder()
                    .id(dept.getId().toString())
                    .name(dept.getName())
                    .build();
        } catch (Exception e) {
            return null;
        }
    }
}
