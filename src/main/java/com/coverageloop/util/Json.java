package com.coverageloop.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

/** JSON 序列化工具（Gson），字段名与 Electron 版 JSON 文件一致 */
public final class Json {

    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Gson COMPACT = new GsonBuilder().disableHtmlEscaping().create();

    private Json() {
    }

    public static String toJson(Object value) {
        return PRETTY.toJson(value);
    }

    public static String toCompactJson(Object value) {
        return COMPACT.toJson(value);
    }

    public static <T> T fromJson(String content, Class<T> type) {
        try {
            return PRETTY.fromJson(content, type);
        } catch (JsonParseException error) {
            throw new IllegalArgumentException("JSON 解析失败：" + error.getMessage(), error);
        }
    }

    public static boolean isJsonObject(String content) {
        try {
            return JsonParser.parseString(content).isJsonObject();
        } catch (JsonParseException error) {
            return false;
        }
    }
}
