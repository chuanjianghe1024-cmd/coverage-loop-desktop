package com.coverageloop.model;

/** 范围模式：include / exclude */
public enum ScopeMode {
    include,
    exclude;

    public static ScopeMode parse(String value) {
        return "exclude".equalsIgnoreCase(value) ? exclude : include;
    }

    public String json() {
        return name();
    }
}
