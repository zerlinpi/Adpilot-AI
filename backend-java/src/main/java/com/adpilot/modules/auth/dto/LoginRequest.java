package com.adpilot.modules.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class LoginRequest {

    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email format")
    private String email;

    @NotBlank(message = "Password is required")
    private String password;

    /** TOTP second factor; required only when 2FA is enabled for the account (Req 11.2.2). */
    private String twoFactorCode;

    /** Optional "remember me" flag sent by the login form; not required. */
    private Boolean remember;
}
