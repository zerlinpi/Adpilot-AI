package com.adpilot.modules.alert.enums;

/**
 * Alert categories raised by the {@link com.adpilot.modules.alert.service.AlertEngine}
 * (Req 10.1.1&ndash;10.1.4). The {@link #code} is the value persisted in
 * {@code alerts.alert_type}.
 */
public enum AlertType {

    STOCKOUT("stockout"),
    ACOS("acos"),
    BUYBOX("buybox"),
    NEGATIVE_REVIEW("negative_review");

    private final String code;

    AlertType(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
