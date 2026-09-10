package com.coverageloop.model;

/** 单个配置范围（模块 / 包 / 类）的覆盖率汇总 */
public class CoverageScopeResult {
    public String modulePath;
    /** "module" | "package" | "class" */
    public String kind;
    public String pattern;
    public int classCount;
    public int coveredLines;
    public int missedLines;
    public Double lineCoverage;
}
