package com.coverageloop.model;

/** 自动循环单轮摘要 */
public class CoverageLoopRoundSummary {
    public String runId;
    public int round;
    public Integer exitCode;
    public TestExecutionStatus testStatus;
    public int pendingClassCount;
    public String logPath;
    public String coverageSnapshotPath;
}
