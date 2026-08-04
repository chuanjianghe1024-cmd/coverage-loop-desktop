package com.coverageloop.model;

/** 单个 Include / Exclude 范围规则 */
public class CoverageScope {
    public String modulePath;
    public ScopeMode mode;
    public ScopeKind kind;
    public String pattern;

    public CoverageScope() {
    }

    public CoverageScope(String modulePath, ScopeMode mode, ScopeKind kind, String pattern) {
        this.modulePath = modulePath;
        this.mode = mode;
        this.kind = kind;
        this.pattern = pattern;
    }

    public CoverageScope copy() {
        return new CoverageScope(modulePath, mode, kind, pattern);
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof CoverageScope)) return false;
        CoverageScope scope = (CoverageScope) other;
        return java.util.Objects.equals(modulePath, scope.modulePath)
                && mode == scope.mode
                && kind == scope.kind
                && java.util.Objects.equals(pattern, scope.pattern);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(modulePath, mode, kind, pattern);
    }
}
