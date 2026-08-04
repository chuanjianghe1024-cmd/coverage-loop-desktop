package com.coverageloop.model;

import java.util.ArrayList;
import java.util.List;

/** 统计分组结果 */
public class CoverageStatisticsGroupResult {
    public String groupId;
    public String groupName;
    public String confirmedAt;
    public int moduleCount;
    public int classCount;
    public int coveredLines;
    public int missedLines;
    public Double lineCoverage;
    public List<CoverageStatisticsModuleResult> modules = new ArrayList<>();
}
