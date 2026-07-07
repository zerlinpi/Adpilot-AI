package com.adpilot.common.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum ResultCode {

    // General HTTP codes
    SUCCESS(200, "Success"),
    BAD_REQUEST(400, "Bad Request"),
    UNAUTHORIZED(401, "Unauthorized"),
    FORBIDDEN(403, "Forbidden"),
    NOT_FOUND(404, "Not Found"),
    INTERNAL_ERROR(500, "Internal Server Error"),
    VALIDATION_ERROR(422, "Validation Error"),

    // Business errors
    GOAL_NOT_FOUND(1001, "Goal not found"),
    CAMPAIGN_NOT_FOUND(1002, "Campaign not found"),
    PRODUCT_NOT_FOUND(1003, "Product not found"),
    STORE_NOT_FOUND(1004, "Store not found"),
    USER_NOT_FOUND(1005, "User not found"),
    DUPLICATE_EMAIL(1006, "Email already exists"),
    INVALID_CREDENTIALS(1007, "Invalid credentials"),
    PERMISSION_DENIED(1008, "Permission denied"),
    MARKETPLACE_TIMEZONE_NOT_CONFIGURED(1009, "Marketplace timezone is not configured");

    private final int code;
    private final String message;
}
