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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Timing-enumeration hardening tests for {@link AuthServiceImpl#login} (audit L1).
 *
 * <p>An unknown email must not return faster than a known email: the service runs
 * a dummy bcrypt comparison on the unknown-user (and missing-hash) branch so both
 * paths spend comparable CPU time, then throws the <em>same</em>
 * {@code INVALID_CREDENTIALS} 401. These tests verify the outcome is unchanged and
 * that {@code passwordEncoder.matches} is exercised even when no user exists, which
 * is what equalizes the timing.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LoginTimingEnumerationTest {

    private static final String EMAIL = "ghost@example.com";
    private static final String PASSWORD = "correct-horse";

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

    // Audit L1: an unknown email still throws the same INVALID_CREDENTIALS 401 ...
    @Test
    void unknownEmail_throwsSameInvalidCredentials401() {
        when(userService.getUserByEmail(EMAIL)).thenReturn(Optional.empty());

        BusinessException ex = catchThrowableOfType(
                () -> authService.login(request(PASSWORD), "203.0.113.1", "JUnit"), BusinessException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getStatus()).isEqualTo(401);
        assertThat(ex.getCode()).isEqualTo("INVALID_CREDENTIALS");
        assertThat(ex.getMessage()).isEqualTo("Invalid email or password");
        verify(jwtUtil, never()).generateToken(anyString(), anyString(), anyList());
    }

    // ... and the dummy-compare path is exercised: passwordEncoder.matches is invoked
    // even for an unknown user (against a constant hash), so the branch is not a fast
    // short-circuit. The raw password provided is passed through unchanged.
    @Test
    void unknownEmail_stillRunsBcryptCompare() {
        when(userService.getUserByEmail(EMAIL)).thenReturn(Optional.empty());

        catchThrowableOfType(
                () -> authService.login(request(PASSWORD), "203.0.113.1", "JUnit"), BusinessException.class);

        ArgumentCaptor<String> raw = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(passwordEncoder, times(1)).matches(raw.capture(), hash.capture());
        assertThat(raw.getValue()).isEqualTo(PASSWORD);
        // A syntactically-valid bcrypt hash so the encoder does real work, not a no-op.
        assertThat(hash.getValue()).startsWith("$2a$");
    }

    // A known user whose stored password hash is null/blank must also run the dummy
    // compare (not short-circuit) and fail with the identical INVALID_CREDENTIALS 401.
    @Test
    void knownUserWithBlankHash_runsDummyCompareAndFails() {
        User user = User.builder()
                .id(UUID.randomUUID())
                .orgId(UUID.randomUUID())
                .email(EMAIL)
                .passwordHash("")   // ineligible: no usable password
                .status("active")
                .build();
        when(userService.getUserByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(loginSecurityService.isLocked(user)).thenReturn(false);

        BusinessException ex = catchThrowableOfType(
                () -> authService.login(request(PASSWORD), "203.0.113.1", "JUnit"), BusinessException.class);

        assertThat(ex).isNotNull();
        assertThat(ex.getStatus()).isEqualTo(401);
        assertThat(ex.getCode()).isEqualTo("INVALID_CREDENTIALS");
        // The dummy compare is exercised even though the account has no usable hash.
        verify(passwordEncoder, times(1)).matches(eq(PASSWORD), anyString());
        verify(loginSecurityService).recordFailedAttempt(eq(user), anyString(), anyString(), anyString(), anyString());
        verify(jwtUtil, never()).generateToken(anyString(), anyString(), anyList());
    }

    private LoginRequest request(String password) {
        LoginRequest req = new LoginRequest();
        req.setEmail(EMAIL);
        req.setPassword(password);
        return req;
    }
}
