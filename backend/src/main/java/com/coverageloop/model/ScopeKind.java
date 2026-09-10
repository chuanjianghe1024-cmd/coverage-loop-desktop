package com.coverageloop.model;

import com.google.gson.annotations.SerializedName;

/** 范围粒度：包 / 类 */
public enum ScopeKind {
    @SerializedName("package")
    package_,
    @SerializedName("class")
    class_;

    public static ScopeKind parse(String value) {
        return "class".equalsIgnoreCase(value) ? class_ : package_;
    }

    /** 与 TypeScript 端持久化的字符串一致 */
    public String json() {
        return this == package_ ? "package" : "class";
    }
}
