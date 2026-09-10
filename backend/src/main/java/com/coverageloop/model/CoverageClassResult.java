package com.coverageloop.model;

/** 单个类的覆盖率结果 */
public class CoverageClassResult {
    public String modulePath;
    public String packageName;
    public String className;
    public String qualifiedName;
    public int coveredLines;
    public int missedLines;
    /** 行覆盖率百分比（0-100），无数据时为 null */
    public Double lineCoverage;

    public CoverageClassResult() {
    }

    public CoverageClassResult(String modulePath, String packageName, String className, String qualifiedName,
                               int coveredLines, int missedLines, Double lineCoverage) {
        this.modulePath = modulePath;
        this.packageName = packageName;
        this.className = className;
        this.qualifiedName = qualifiedName;
        this.coveredLines = coveredLines;
        this.missedLines = missedLines;
        this.lineCoverage = lineCoverage;
    }

    public CoverageClassResult copy() {
        return new CoverageClassResult(modulePath, packageName, className, qualifiedName,
                coveredLines, missedLines, lineCoverage);
    }
}
