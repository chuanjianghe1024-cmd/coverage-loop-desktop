package com.coverageloop.model;

import java.util.ArrayList;
import java.util.List;

/** Agent 单轮执行结果 */
public class AgentRoundResult {
    public AgentExecutionStatus status;
    /** "coverage" | "repair" */
    public String mode;
    public String sessionId;
    public String provider;
    public int round;
    public Integer exitCode;
    public String signal;
    public String logPath;
    public String promptPath;
    public String failedClassesPath;
    public String changedTestsPath;
    public List<String> changedTestFiles = new ArrayList<>();
    public boolean completionMarkerSeen;
    public int selectedClassCount;
    public String startedAt;
    public String finishedAt;
}
