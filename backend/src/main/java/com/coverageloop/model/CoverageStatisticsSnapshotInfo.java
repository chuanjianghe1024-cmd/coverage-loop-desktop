package com.coverageloop.model;

/** 统计快照信息 */
public class CoverageStatisticsSnapshotInfo {
    public String runId;
    public String startedAt;
    public String finishedAt;
    public String logPath;
    public String runDirectory;
    public String coverageSnapshotPath;
    public int moduleCount;
    public TestExecutionResult tests = new TestExecutionResult();
}
