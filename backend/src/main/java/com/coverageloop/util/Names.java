package com.coverageloop.util;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/** 命名与时间工具 */
public final class Names {

    private Names() {
    }

    /** 与 Electron 版一致的配置 ID 归一化：小写、非 [a-z0-9_-] 转 '-'、去掉首尾 '-' */
    public static String normalizedId(String value, String fallback) {
        String id = value.trim().toLowerCase()
                .replaceAll("[^a-z0-9_-]+", "-")
                .replaceAll("^-+|-+$", "");
        return id.isEmpty() ? fallback : id;
    }

    public static String normalizedIdOrThrow(String value, String label) {
        String id = normalizedId(value, "");
        if (id.isEmpty()) throw new IllegalArgumentException(label + "不能为空");
        return id;
    }

    /** 执行 ID：时间戳-随机8位，如 20260804-223000-1a2b3c4d */
    public static String newRunId() {
        String timestamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .withZone(ZoneOffset.UTC).format(Instant.now());
        return timestamp + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    public static String timestampId() {
        return DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .withZone(ZoneOffset.UTC).format(Instant.now())
                + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    public static String roundLabel(int round) {
        return String.format("%03d", round);
    }

    public static String nowIso() {
        return Instant.now().toString();
    }
}
