package com.adpilot.modules.auth.service.impl;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.common.security.JwtUtil;
import com.adpilot.common.security.SessionControlService;
import com.adpilot.modules.auth.dto.LoginRequest;
import com.adpilot.modules.auth.service.LoginSecurityService;
import com.adpilot.modules.auth.service.ReconfirmationService;
import com.adpilot.modules.auth.service.TwoFactorService;
import com.adpilot.modules.user.entity.User;
import com.adpilot.modules.user.mapper.DepartmentMapper;
import com.adpilot.modules.user.mapper.LoginLogMapper;
import com.adpilot.modules.user.mapper.UserDepartmentMapper;
import com.adpilot.modules.user.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Status-code tests for authentication failures via {@link AuthServiceImpl}
 * (optimization M1). Invalid credentials (unknown user or wrong password) and a
 * missing current user carry the correct HTTP status via {@link BusinessException}
 * (401 / 404) instead of surfacing as a generic 500.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuthServiceStatusCodeTest {

    private static final String EMAIL = "user@example.com";
    private static final String PASSWORD = "correct-horse";
    private static final String PASSWORD_HASH = "{bcrypt}hash";

    @Mock private UserService userService;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private JwtUtil jwtUtil;
    @Mock private LoginSecurityService loginSecurityService;
    @Mock private com.adpilot.modules.auth.service.LoginRateLimiter loginRateLimiter;
    @Mock private TwoFactorService twoFactorService;
    @Mock private ReconfirmationService reconfirmationService;
    @Mock private SessionControlService sessionControlService;
    @Mock private UserDepartmentMapper userDepartmentMapper;
    @Mock private DepartmentMapper departmentMapper;
    @Mock private LoginLogMapper loginLogMapper;
    @Mock private com.adpilot.modules.audit.service.AuditLogService auditLogService;

    private AuthServiceImpl authService;

    @BeforeEach
    void setUp() {
        authService = new AuthServiceImpl(
                userService, passwordEncoder, jwtUtil, loginSecurityService, loginRateLimiter,
                twoFactorService, reconfirmationService, sessionControlService,
                userDepartmentMapper, departmentMapper, loginLogMapper, auditLogService);
    }

    // Unknown user -> invalid credentials -> 401 (never a 500 or user enumeration).
    @Test
    void login_unknownUser_throws401() {
        when(userService.getUserByEmail(EMAIL)).thenReturn(Optional.empty());

        BusinessException ex = catchThrowableOfType(
                () -> authService.login(request(), "203.0.113.1", "JUnit"), BusinessException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getStatus()).isEqualTo(401);
        assertThat(ex.getCode()).isEqualTo("INVALID_CREDENTIALS");
        verify(jwtUtil, never()).generateToken(anyString(), anyString(), org.mockito.ArgumentMatchers.anyList());
    }

    // Wrong password -> invalid credentials -> 401.
    @Test
    void login_wrongPassword_throws401() {
        User user = User.builder()
                .id(UUID.randomUUID())
                .orgId(UUID.randomUUID())
                .email(EMAIL)
                .passwordHash(PASSWORD_HASH)
                .status("active")
                .build();
        when(userService.getUserByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(loginSecurityService.isLocked(user)).thenReturn(false);
        when(passwordEncoder.matches(PASSWORD, PASSWORD_HASH)).thenReturn(false);

        BusinessException ex = catchThrowableOfType(
                () -> authService.login(request(), "203.0.113.1", "JUnit"), BusinessException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getStatus()).isEqualTo(401);
        assertThat(ex.getCode()).isEqualTo("INVALID_CREDENTIALS");
        verify(jwtUtil, never()).generateToken(anyString(), anyString(), org.mockito.ArgumentMatchers.anyList());
    }

    // Audit L3: a client IP over the per-IP threshold is throttled BEFORE any
    // credential check (no user lookup, no token) with a generic 429.
    @Test
    void login_ipRateLimited_throws429BeforeCredentialCheck() {
        when(loginRateLimiter.isBlocked("203.0.113.1")).thenReturn(true);

        BusinessException ex = catchThrowableOfType(
                () -> authService.login(request(), "203.0.113.1", "JUnit"), BusinessException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getStatus()).isEqualTo(429);
        assertThat(ex.getCode()).isEqualTo("TOO_MANY_REQUESTS");
        // The throttle short-circuits before the account is even looked up.
        verify(userService, never()).getUserByEmail(anyString());
        verify(jwtUtil, never()).generateToken(anyString(), anyString(), org.mockito.ArgumentMatchers.anyList());
    }

    // getCurrentUser for a missing user -> 404.
    @Test
    void getCurrentUser_missing_throws404() {
        String userId = UUID.randomUUID().toString();
        when(userService.getUserById(userId)).thenReturn(Optional.empty());

        BusinessException ex = catchThrowableOfType(
                () -> authService.getCurrentUser(userId), BusinessException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getStatus()).isEqualTo(404);
        assertThat(ex.getCode()).isEqualTo("USER_NOT_FOUND");
    }

    private LoginRequest request() {
        LoginRequest req = new LoginRequest();
        req.setEmail(EMAIL);
        req.setPassword(PASSWORD);
        return req;
    }
}
