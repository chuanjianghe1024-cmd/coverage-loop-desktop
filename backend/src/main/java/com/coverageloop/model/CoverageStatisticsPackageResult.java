package com.coverageloop.model;

import java.util.ArrayList;
import java.util.List;

/** 统计分组内单个包的结果 */
public class CoverageStatisticsPackageResult {
    public String packageName;
    public int classCount;
    public int coveredLines;
    public int missedLines;
    public Double lineCoverage;
    public List<CoverageClassResult> classes = new ArrayList<>();
}
