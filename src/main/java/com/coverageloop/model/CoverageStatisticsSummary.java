package com.coverageloop.model;

import java.util.ArrayList;
import java.util.List;

/** 覆盖率统计汇总（跨分组去重） */
public class CoverageStatisticsSummary {
    public String snapshotId;
    public String generatedAt;
    public int confirmedGroupCount;
    public int classCount;
    public int coveredLines;
    public int missedLines;
    public Double lineCoverage;
    public List<CoverageStatisticsGroupResult> groups = new ArrayList<>();
}
