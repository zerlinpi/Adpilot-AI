package com.adpilot.modules.auth.service.impl;

import com.adpilot.common.exception.BusinessException;
import com.adpilot.common.enums.ResultCode;
import com.adpilot.modules.auth.service.PasswordPolicyService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Default password-strength policy implementation (Req 11.1.3).
 *
 * <p>The policy is fully configurable via {@code adpilot.security.password.*}
 * properties. By default a strong password must be at least 8 characters and
 * contain an uppercase letter, a lowercase letter, and a digit.
 */
@Slf4j
@Service
public class PasswordPolicyServiceImpl implements PasswordPolicyService {

    @Value("${adpilot.security.password.min-length:8}")
    private int minLength;

    @Value("${adpilot.security.password.max-length:128}")
    private int maxLength;

    @Value("${adpilot.security.password.require-uppercase:true}")
    private boolean requireUppercase;

    @Value("${adpilot.security.password.require-lowercase:true}")
    private boolean requireLowercase;

    @Value("${adpilot.security.password.require-digit:true}")
    private boolean requireDigit;

    @Value("${adpilot.security.password.require-special:false}")
    private boolean requireSpecial;

    @Override
    public boolean isValid(String password) {
        return validateAndCollect(password).isEmpty();
    }

    @Override
    public List<String> validateAndCollect(String password) {
        List<String> violations = new ArrayList<>();

        if (password == null || password.isEmpty()) {
            violations.add("Password must not be empty");
            return violations;
        }

        if (password.length() < minLength) {
            violations.add("Password must be at least " + minLength + " characters long");
        }
        if (password.length() > maxLength) {
            violations.add("Password must be at most " + maxLength + " characters long");
        }
        if (requireUppercase && password.chars().noneMatch(Character::isUpperCase)) {
            violations.add("Password must contain at least one uppercase letter");
        }
        if (requireLowercase && password.chars().noneMatch(Character::isLowerCase)) {
            violations.add("Password must contain at least one lowercase letter");
        }
        if (requireDigit && password.chars().noneMatch(Character::isDigit)) {
            violations.add("Password must contain at least one digit");
        }
        if (requireSpecial && password.chars().allMatch(Character::isLetterOrDigit)) {
            violations.add("Password must contain at least one special character");
        }

        return violations;
    }

    @Override
    public void validate(String password) {
        List<String> violations = validateAndCollect(password);
        if (!violations.isEmpty()) {
            throw new BusinessException(
                    String.valueOf(ResultCode.VALIDATION_ERROR.getCode()),
                    "Password does not meet the strength policy: " + String.join("; ", violations));
        }
    }
}
