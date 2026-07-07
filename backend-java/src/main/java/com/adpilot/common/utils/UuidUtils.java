package com.adpilot.common.utils;

import java.util.UUID;

public class UuidUtils {
    public static UUID fromString(String str) {
        if (str == null || str.isBlank()) return null;
        try {
            return UUID.fromString(str);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
