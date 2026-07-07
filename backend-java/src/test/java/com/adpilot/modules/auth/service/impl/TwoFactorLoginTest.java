package com.adpilot.modules.auth.service.impl;

import com.adpilot.common.security.JwtUtil;
import com.adpilot.common.security.SessionControlService;
import com.adpilot.common.security.TotpVerifier;
import com.adpilot.common.utils.CryptoUtil;
import com.adpilot.modules.auth.dto.LoginRequest;
import com.adpilot.modules.auth.dto.LoginResponse;
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
import org.mockito.quality.Strictness;
import org.mockito.junit.jupiter.MockitoSettings;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for two-factor-enforced login via {@link AuthServiceImpl} (task 22.6).
 *
 * <p>When 2FA is enabled for an account, login completes only with a valid TOTP
 * second factor; an invalid or missing code is rejected (Req 11.2.2).
 *
 * <p>The two-factor stack is wired with the <em>real</em> {@link TwoFactorServiceImpl},
 * {@link TotpVerifier} (RFC 6238) and {@link CryptoUtil}, so the test derives a
 * genuinely valid code from the same secret/algorithm the server uses rather than
 * stubbing verification. Data-access collaborators are mocked.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TwoFactorLoginTest {

    /** Base32 TOTP secret (RFC 4648), stored as legacy plaintext on the user row. */
    private static final String SECRET = "JBSWY3DPEHPK3PXP";
    private static final String EMAIL = "2fa-user@example.com";
    private static final String PASSWORD = "correct-horse";
    private static final String PASSWORD_HASH = "{bcrypt}hash";

    @Mock
    private UserService userService;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private JwtUtil jwtUtil;
    @Mock
    private LoginSecurityService loginSecurityService;
    @Mock
    private com.adpilot.modules.auth.service.LoginRateLimiter loginRateLimiter;
    @Mock
    private ReconfirmationService reconfirmationService;
    @Mock
    private SessionControlService sessionControlService;
    @Mock
    private UserDepartmentMapper userDepartmentMapper;
    @Mock
    private DepartmentMapper departmentMapper;
    @Mock
    private LoginLogMapper loginLogMapper;
    @Mock
    private com.adpilot.modules.audit.service.AuditLogService auditLogService;

    private TotpVerifier totpVerifier;
    private TwoFactorService twoFactorService;
    private AuthServiceImpl authService;

    private User user;

    @BeforeEach
    void setUp() {
        totpVerifier = new TotpVerifier();
        twoFactorService = new TwoFactorServiceImpl(totpVerifier, new CryptoUtil("test-crypto-secret"));

        authService = new AuthServiceImpl(
                userService, passwordEncoder, jwtUtil, loginSecurityService, loginRateLimiter,
                twoFactorService, reconfirmationService, sessionControlService,
                userDepartmentMapper, departmentMapper, loginLogMapper, auditLogService);
        ReflectionTestUtils.setField(authService, "jwtExpiration", 86_400_000L);

        user = User.builder()
                .id(UUID.randomUUID())
                .orgId(UUID.randomUUID())
                .email(EMAIL)
                .name("2FA User")
                .passwordHash(PASSWORD_HASH)
                .status("active")
                .twofaEnabled(true)
                .twofaSecret(SECRET)
                .build();

        // Credentials and account state are valid; only the second factor varies.
        when(userService.getUserByEmail(EMAIL)).thenReturn(Optional.of(user));
        when(loginSecurityService.isLocked(user)).thenReturn(false);
        when(passwordEncoder.matches(PASSWORD, PASSWORD_HASH)).thenReturn(true);
    }

    // Req 11.2.2: a valid TOTP second factor completes login when 2FA is enabled.
    @Test
    void loginSucceedsWithValidTwoFactorCode() {
        String validCode = totpVerifier.generateCurrent(SECRET);

        when(userService.getUserPrimaryRole(user.getId().toString())).thenReturn("operator");
        when(userService.getUserPermissions(user.getId().toString())).thenReturn(List.of("campaign:read"));
        when(jwtUtil.generateToken(anyString(), anyString(), org.mockito.ArgumentMatchers.anyList()))
                .thenReturn("issued-jwt");

        LoginResponse response = authService.login(request(validCode), "203.0.113.1", "JUnit");

        assertThat(response).isNotNull();
        assertThat(response.getToken()).isEqualTo("issued-jwt");
        verify(loginSecurityService).recordSuccessfulLogin(
                org.mockito.ArgumentMatchers.eq(user), anyString(), anyString(), anyString());
    }

    // Req 11.2.2: an invalid TOTP code is rejected and no token is issued.
    @Test
    void loginIsRejectedWithInvalidTwoFactorCode() {
        String wrongCode = aCodeRejectedBy(totpVerifier, SECRET);

        assertThatThrownBy(() -> authService.login(request(wrongCode), "203.0.113.1", "JUnit"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("two-factor");

        verify(loginSecurityService).recordRejectedAttempt(
                org.mockito.ArgumentMatchers.eq(user), anyString(), anyString(), anyString(), anyString());
        verify(jwtUtil, never()).generateToken(anyString(), anyString(), org.mockito.ArgumentMatchers.anyList());
    }

    // Req 11.2.2: a missing TOTP code is rejected when 2FA is enabled.
    @Test
    void loginIsRejectedWithMissingTwoFactorCode() {
        assertThatThrownBy(() -> authService.login(request(null), "203.0.113.1", "JUnit"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("two-factor");

        verify(jwtUtil, never()).generateToken(anyString(), anyString(), org.mockito.ArgumentMatchers.anyList());
    }

    private LoginRequest request(String twoFactorCode) {
        LoginRequest req = new LoginRequest();
        req.setEmail(EMAIL);
        req.setPassword(PASSWORD);
        req.setTwoFactorCode(twoFactorCode);
        return req;
    }

    /** Find a 6-digit code that the verifier rejects right now (deterministic). */
    private static String aCodeRejectedBy(TotpVerifier verifier, String secret) {
        for (int candidate = 0; candidate < 1_000_000; candidate++) {
            String code = String.format("%06d", candidate);
            if (!verifier.verify(secret, code)) {
                return code;
            }
        }
        throw new IllegalStateException("no rejected code found");
    }
}
