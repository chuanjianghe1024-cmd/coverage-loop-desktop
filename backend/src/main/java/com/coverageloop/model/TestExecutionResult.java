package com.coverageloop.model;

import java.util.ArrayList;
import java.util.List;

/** 整体测试执行结果 */
public class TestExecutionResult {
    public TestExecutionStatus status;
    public boolean continuedAfterFailure;
    public int tests;
    public int failures;
    public int errors;
    public int skipped;
    public List<TestModuleResult> modules = new ArrayList<>();
    public String reportArchivePath;
    public String message;
    /** Structured build diagnostics; absent in older saved runs. */
    public String failureKind;
}
