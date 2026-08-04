package com.coverageloop.util;

/** 文本工具 */
public final class Text {

    private Text() {
    }

    private static final String ANSI_PATTERN = "\u001b\\[[0-?]*[ -/]*[@-~]";

    public static String stripAnsi(String value) {
        return value.replaceAll(ANSI_PATTERN, "");
    }

    /** 秒数格式化为 HH:MM:SS */
    public static String elapsedText(long milliseconds) {
        long total = Math.max(0, milliseconds / 1000);
        long hours = total / 3600;
        long minutes = (total % 3600) / 60;
        long seconds = total % 60;
        return String.format("%02d:%02d:%02d", hours, minutes, seconds);
    }

    /** 覆盖率显示：null 显示 "—" */
    public static String coverageText(Double value) {
        return value == null ? "—" : value + "%";
    }
}
