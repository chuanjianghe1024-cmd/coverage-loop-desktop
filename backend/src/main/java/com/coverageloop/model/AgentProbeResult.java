package com.coverageloop.model;

/** Agent 探活结果 */
public class AgentProbeResult {
    public AgentExecutionStatus status;
    public String provider;
    public String executable;
    public Integer exitCode;
    public String signal;
    public String failureKind;
    public String response;
    public String logPath;
    public String startedAt;
    public String finishedAt;
}
