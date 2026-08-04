package com.coverageloop.model;

import java.util.ArrayList;
import java.util.List;

/** 一次 Maven 执行（一轮）的完整结果 */
public class MavenRunResult {
    public Integer exitCode;
    public String signal;
    public MavenCommandPreview command;
    public PreInstall preInstall = new PreInstall();
    public String runId;
    public int round;
    public String runDirectory;
    public String logPath;
    public String coverageSnapshotPath;
    public String startedAt;
    public String finishedAt;
    public List<CoverageModuleResult> coverage = new ArrayList<>();
    public CoverageGroups groups = new CoverageGroups();
    public TestExecutionResult tests = new TestExecutionResult();

    public static class PreInstall {
        public boolean attempted;
        public Integer exitCode;
    }
}
