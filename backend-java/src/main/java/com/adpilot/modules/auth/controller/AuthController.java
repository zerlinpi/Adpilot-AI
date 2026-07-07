package com.adpilot.modules.auth.controller;

import com.adpilot.common.security.CurrentUser;
import com.adpilot.common.security.RequirePermission;
import com.adpilot.modules.auth.dto.ChangePasswordRequest;
import com.adpilot.modules.auth.dto.LoginRequest;
import com.adpilot.modules.auth.dto.LoginResponse;
import com.adpilot.modules.auth.dto.ReconfirmRequest;
import com.adpilot.modules.auth.dto.UserInfo;
import com.adpilot.modules.auth.service.AuthService;
import com.adpilot.common.security.SessionControlService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final SessionControlService sessionControlService;

    /**
     * POST /api/auth/login
     * Login with email and password, return JWT token.
     */
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request,
                                               HttpServletRequest httpRequest) {
        log.info("Login request received for email: {}", request.getEmail());
        String ip = extractClientIp(httpRequest);
        String ua = httpRequest.getHeader("User-Agent");
        LoginResponse response = authService.login(request, ip, ua);
        return ResponseEntity.ok(response);
    }

    private String extractClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        String real = request.getHeader("X-Real-IP");
        if (real != null && !real.isBlank()) {
            return real;
        }
        return request.getRemoteAddr();
    }

    /**
     * GET /api/auth/me
     * Get current user info from JWT token.
     */
    @GetMapping("/me")
    public ResponseEntity<UserInfo> getCurrentUser(@AuthenticationPrincipal CurrentUser currentUser) {
        UserInfo userInfo = authService.getCurrentUser(currentUser.getUserId());
        return ResponseEntity.ok(userInfo);
    }

    /**
     * POST /api/auth/logout
     * Invalidate the presented token so it can no longer authorize requests
     * (Req 11.2.1). The token's jti is denylisted in Redis until it would expire.
     */
    @PostMapping("/logout")
    public ResponseEntity<Map<String, String>> logout(@AuthenticationPrincipal CurrentUser currentUser,
                                                       HttpServletRequest httpRequest) {
        log.info("User logged out: {}", currentUser != null ? currentUser.getEmail() : "unknown");
        String token = extractToken(httpRequest);
        if (StringUtils.hasText(token)) {
            sessionControlService.revokeToken(token);
        }
        return ResponseEntity.ok(Map.of("message", "Logged out successfully"));
    }

    /**
     * POST /api/auth/reconfirm
     * Re-confirm the current user's identity (password + 2FA when enabled) to
     * open a short-lived window for performing sensitive actions (Req 11.2.3).
     */
    @PostMapping("/reconfirm")
    public ResponseEntity<Map<String, String>> reconfirm(@AuthenticationPrincipal CurrentUser currentUser,
                                                          @Valid @RequestBody ReconfirmRequest request) {
        authService.reconfirmIdentity(currentUser.getUserId(),
                request.getPassword(), request.getTwoFactorCode());
        return ResponseEntity.ok(Map.of("message", "Identity re-confirmed"));
    }

    /**
     * POST /api/auth/change-password
     * Change the current user's password after verifying the current password.
     */
    @PostMapping("/change-password")
    public ResponseEntity<Map<String, String>> changePassword(@AuthenticationPrincipal CurrentUser currentUser,
                                                              @Valid @RequestBody ChangePasswordRequest request) {
        authService.changePassword(currentUser.getUserId(),
                request.getCurrentPassword(), request.getNewPassword());
        return ResponseEntity.ok(Map.of("message", "Password changed successfully"));
    }

    /**
     * POST /api/auth/users/{userId}/invalidate-sessions
     * Administratively terminate all of a user's active sessions (Req 11.2.4).
     */
    @PostMapping("/users/{userId}/invalidate-sessions")
    @RequirePermission("user:manage")
    public ResponseEntity<Map<String, String>> invalidateUserSessions(@PathVariable String userId) {
        authService.invalidateUserSessions(userId);
        return ResponseEntity.ok(Map.of("message", "User sessions invalidated"));
    }

    private String extractToken(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }
}
