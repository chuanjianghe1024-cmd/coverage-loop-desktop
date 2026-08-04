package com.coverageloop.model;

import java.util.ArrayList;
import java.util.List;

/** 覆盖率统计配置（.coverage-loop/statistics/config.json） */
public class CoverageStatisticsConfig {
    public int schemaVersion = 2;
    public String rootPomPath;
    public List<CoverageStatisticsGroup> groups = new ArrayList<>();
    public String updatedAt;
}
