package com.coverageloop.model;

import java.util.ArrayList;
import java.util.List;

/** 自动循环整体结果 */
public class CoverageLoopResult {
    public AgentProbeResult probe;
    public List<CoverageLoopRoundSummary> rounds = new ArrayList<>();
    public List<AgentRoundResult> agentRounds = new ArrayList<>();
    public MavenRunResult latest;
    public CoverageLoopStopReason stopReason;
    public String message;
}
