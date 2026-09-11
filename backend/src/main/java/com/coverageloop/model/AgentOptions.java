package com.coverageloop.model;

import java.util.ArrayList;
import java.util.List;

/** Agent（Hermes / OpenCode）选项 */
public class AgentOptions {
    public boolean enabled = false;
    public String provider = "hermes";
    public String model = "";
    public int maxRounds = 5;
    public boolean runUntilTarget = true;
    public int retryDelaySeconds = 60;
    public int maxRetryDelaySeconds = 600;
    public String executable = "";
    public List<String> extraArgs = new ArrayList<>();
    public int batchSize = 3;
    public int timeoutMinutes = 30;
    public boolean autoApprove = true;
    public int maxSameFailures = 3;
    public int heartbeatSeconds = 10;
    public boolean allowProductionChanges = false;
    public String hermesProvider = "";
    public String opencodeAgent = "";
    public String opencodeAttach = "";
    public String coveragePromptTemplate = "";
    public String repairPromptTemplate = "";

    public AgentOptions copy() {
        AgentOptions copy = new AgentOptions();
        copy.enabled = enabled;
        copy.provider = provider;
        copy.model = model;
        copy.maxRounds = maxRounds;
        copy.runUntilTarget = runUntilTarget;
        copy.retryDelaySeconds = retryDelaySeconds;
        copy.maxRetryDelaySeconds = maxRetryDelaySeconds;
        copy.executable = executable;
        copy.extraArgs = new ArrayList<>(extraArgs);
        copy.batchSize = batchSize;
        copy.timeoutMinutes = timeoutMinutes;
        copy.autoApprove = autoApprove;
        copy.maxSameFailures = maxSameFailures;
        copy.heartbeatSeconds = heartbeatSeconds;
        copy.allowProductionChanges = allowProductionChanges;
        copy.hermesProvider = hermesProvider;
        copy.opencodeAgent = opencodeAgent;
        copy.opencodeAttach = opencodeAttach;
        copy.coveragePromptTemplate = coveragePromptTemplate;
        copy.repairPromptTemplate = repairPromptTemplate;
        return copy;
    }
}
