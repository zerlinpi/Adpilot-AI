package com.adpilot.common.utils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class DateUtils {
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATETIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static String format(LocalDate date) {
        return date == null ? null : date.format(DATE_FMT);
    }

    public static String format(LocalDateTime dateTime) {
        return dateTime == null ? null : dateTime.format(DATETIME_FMT);
    }

    public static LocalDate parseDate(String str) {
        return str == null ? null : LocalDate.parse(str, DATE_FMT);
    }

    public static LocalDateTime parseDateTime(String str) {
        return str == null ? null : LocalDateTime.parse(str, DATETIME_FMT);
    }
}
