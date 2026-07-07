package com.adpilot.modules.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Body for {@code POST /api/auth/reconfirm}: the user re-confirms their identity
 * before performing a sensitive action (Req 11.2.3) by re-entering their
 * password and, when 2FA is enabled, a current TOTP code.
 */
@Data
public class ReconfirmRequest {

    @NotBlank(message = "Password is required")
    private String password;

    /** TOTP second factor; required only when 2FA is enabled for the account. */
    private String twoFactorCode;
}
