package com.adpilot.modules.advertising.support;

import com.adpilot.common.enums.ResultCode;
import com.adpilot.common.exception.BusinessException;

/**
 * Configuration error raised when a day-boundary-dependent metric, date range,
 * "today" computation, or scheduling operation is requested for an Active_Store
 * whose Marketplace_Timezone (the IANA {@code timezone} on the
 * {@code MarketplaceEntity}) is unset or invalid.
 *
 * <p>Per Requirement 51.14 the Advertising_Module MUST reject such computations
 * with a configuration error that <em>identifies the missing
 * Marketplace_Timezone</em> rather than silently defaulting to the server
 * timezone. {@link MarketplaceTimezone} throws this exception instead of falling
 * back, so a misconfigured Store produces an actionable, attributable failure.
 */
public class MarketplaceTimezoneNotConfiguredException extends BusinessException {

    /**
     * @param suppliedTimezone the value found on the Marketplace (may be
     *                         {@code null}, blank, or an unrecognized IANA id);
     *                         echoed back so the operator can identify what is
     *                         missing or wrong without guessing
     */
    public MarketplaceTimezoneNotConfiguredException(String suppliedTimezone) {
        super(String.valueOf(ResultCode.MARKETPLACE_TIMEZONE_NOT_CONFIGURED.getCode()),
                buildMessage(suppliedTimezone));
    }

    private static String buildMessage(String suppliedTimezone) {
        if (suppliedTimezone == null || suppliedTimezone.isBlank()) {
            return "Marketplace_Timezone is not configured for the Active_Store; "
                    + "set the marketplace IANA timezone before requesting day-boundary "
                    + "or scheduling computations (the server timezone is never used as a fallback).";
        }
        return "Marketplace_Timezone '" + suppliedTimezone + "' is not a valid IANA timezone "
                + "for the Active_Store; set a valid marketplace IANA timezone before requesting "
                + "day-boundary or scheduling computations (the server timezone is never used as a fallback).";
    }
}
