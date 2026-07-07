package com.adpilot.modules.auth.service;

import java.util.List;

/**
 * Enforces the configured password-strength policy (Req 11.1.3).
 */
public interface PasswordPolicyService {

    /**
     * Check whether a candidate password satisfies the configured strength policy.
     *
     * @param password the raw (plaintext) candidate password
     * @return true if the password is strong enough, false otherwise
     */
    boolean isValid(String password);

    /**
     * Return the list of policy violations for a candidate password.
     * An empty list means the password satisfies the policy.
     *
     * @param password the raw (plaintext) candidate password
     * @return human-readable descriptions of each unmet policy rule
     */
    List<String> validateAndCollect(String password);

    /**
     * Validate a candidate password, throwing if it does not satisfy the policy.
     *
     * @param password the raw (plaintext) candidate password
     * @throws com.adpilot.common.exception.BusinessException if the password is too weak
     */
    void validate(String password);
}
