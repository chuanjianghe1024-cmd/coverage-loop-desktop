package com.coverageloop.model;

/** 单个模块的测试执行结果 */
public class TestModuleResult {
    public String modulePath;
    public TestModuleStatus status;
    public int tests;
    public int failures;
    public int errors;
    public int skipped;
    public int suites;
    public String reportDirectory;
    public int freshReportCount;
}
