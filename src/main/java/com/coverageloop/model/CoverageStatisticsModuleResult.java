package com.coverageloop.model;

import java.util.ArrayList;
import java.util.List;

/** 统计分组内单个模块的结果 */
public class CoverageStatisticsModuleResult {
    public String modulePath;
    public String source;
    public int classCount;
    public int coveredLines;
    public int missedLines;
    public Double lineCoverage;
    public List<CoverageStatisticsPackageResult> packages = new ArrayList<>();
}
