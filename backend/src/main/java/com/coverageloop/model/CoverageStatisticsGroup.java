package com.coverageloop.model;

import java.util.ArrayList;
import java.util.List;

/** 覆盖率统计分组 */
public class CoverageStatisticsGroup {
    public String id;
    public String name;
    public List<String> modulePaths = new ArrayList<>();
    public List<CoverageScope> scopes = new ArrayList<>();
    /** 确认时间，null 表示未确认 */
    public String confirmedAt;
}
