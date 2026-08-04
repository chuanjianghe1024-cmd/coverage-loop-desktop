package com.coverageloop.model;

import java.util.ArrayList;
import java.util.List;

/** 单个模块的覆盖率汇总 */
public class CoverageModuleResult {
    public String modulePath;
    public String reportPath;
    /** "jacoco" | "source-fallback" */
    public String source;
    public int classCount;
    public int coveredLines;
    public int missedLines;
    public Double lineCoverage;
    public List<CoverageClassResult> classes = new ArrayList<>();
    public List<CoverageScopeResult> scopeResults = new ArrayList<>();
}
