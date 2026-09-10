package com.coverageloop.service;

import com.coverageloop.model.AgentExecutionStatus;
import com.coverageloop.model.AgentRoundResult;
import com.coverageloop.model.CoverageLoopResult;
import com.coverageloop.model.CoverageLoopRoundSummary;
import com.coverageloop.model.CoverageLoopStopReason;
import com.coverageloop.model.MavenRunResult;
import com.coverageloop.model.ProjectConfig;
import com.coverageloop.model.TestExecutionStatus;
import com.coverageloop.util.Fs;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** 自动补测循环状态机，与 Electron 版 coverage-loop-runner.ts 一致 */
public class CoverageLoopRunner {

    private volatile boolean stopRequested = false;

    private final java.util.function.Consumer<MavenRunResult> onRound;
    private final java.util.function.Consumer<AgentRoundResult> onAgent;
    private final MavenRunner maven;
    private final AgentRunner agent;

    public CoverageLoopRunner(MavenRunner maven, AgentRunner agent) {
        this(maven, agent, result -> {});
    }

    public CoverageLoopRunner(MavenRunner maven, AgentRunner agent, java.util.function.Consumer<MavenRunResult> onRound) {
        this(maven, agent, onRound, result -> {});
    }

    public CoverageLoopRunner(MavenRunner maven, AgentRunner agent, java.util.function.Consumer<MavenRunResult> onRound, java.util.function.Consumer<AgentRoundResult> onAgent) {
        this.onAgent = onAgent;
        this.onRound = onRound;
        this.maven = maven;
        this.agent = agent;
    }

    private static String stopMessage(CoverageLoopStopReason reason, MavenRunResult latest, int maxRounds) {
        switch (reason) {
            case target_reached:
                return "自动循环完成：第 " + (latest == null ? 0 : latest.round)
                        + " 轮后所有选中类均已达到覆盖率门槛";
            case invalid_report:
                return "本轮未生成有效 JaCoCo 报告或没有命中目标类，停止自动循环";
            case max_rounds:
                return "自动循环已达到最大 " + maxRounds + " 轮，剩余 "
                        + (latest == null ? 0 : latest.groups.pending.size()) + " 个待补充类";
            case maven_failed:
                return "自动循环停止：" + (latest == null ? "Maven 或测试失败" : latest.tests.message);
            case agent_unavailable:
                return "自动循环未启动：Agent 探活未收到 OK";
            case agent_failed:
                return "自动循环停止：第 " + (latest == null ? 0 : latest.round) + " 轮 Agent 执行失败";
            case repeated_maven_failure:
                return "自动循环停止：同一 Maven/测试失败已连续出现 " + maxRounds + " 次，避免无效死循环";
            default:
                return "自动循环已由用户终止";
        }
    }

    private static CoverageLoopRoundSummary roundSummary(MavenRunResult result) {
        CoverageLoopRoundSummary summary = new CoverageLoopRoundSummary();
        summary.runId = result.runId;
        summary.round = result.round;
        summary.exitCode = result.exitCode;
        summary.testStatus = result.tests.status;
        summary.pendingClassCount = result.groups.pending.size();
        summary.logPath = result.logPath;
        summary.coverageSnapshotPath = result.coverageSnapshotPath;
        return summary;
    }

    /** 同一 Maven 失败签名：取日志最后 80 条 [ERROR] 行做 sha256 */
    public static String mavenFailureSignature(MavenRunResult result) {
        String logText = Fs.readStringQuiet(result.logPath);
        if (logText != null) {
            List<String> errorLines = new ArrayList<>();
            for (String line : logText.split("\\r?\\n")) {
                if (line.contains("[ERROR]")) errorLines.add(line);
            }
            int start = Math.max(0, errorLines.size() - 80);
            List<String> tail = new ArrayList<>(errorLines.subList(start, errorLines.size()));
            for (int i = 0; i < tail.size(); i++) {
                tail.set(i, tail.get(i)
                        .replaceFirst("^\\[[^\\]]+\\]\\s+\\[(?:stdout|stderr|system)\\]\\s*", "")
                        .replaceAll("\\b\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?Z\\b", "<timestamp>"));
            }
            String source = String.join("\n", tail);
            if (source.trim().isEmpty() && logText.length() > 12_000) {
                source = logText.substring(logText.length() - 12_000);
            }
            if (source.trim().isEmpty()) source = logText;
            return sha256(source);
        }
        return sha256((result.exitCode == null ? "null" : result.exitCode) + ":"
                + (result.signal == null ? "none" : result.signal) + ":"
                + result.tests.status.json() + ":" + result.tests.message);
    }

    private static String sha256(String source) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(source.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception error) {
            return String.valueOf(source.hashCode());
        }
    }

    /** 自动循环：探活 → Maven 基线 → Agent 补测/修复 → 下一轮 Maven */
    public CoverageLoopResult run(ProjectConfig config) throws Exception {
        if (!config.agent.enabled) throw new IllegalArgumentException("请先启用 Hermes 或 OpenCode Agent");
        if (config.selectedModulePaths == null || config.selectedModulePaths.isEmpty()) {
            throw new IllegalArgumentException("请至少选择一个模块");
        }
        stopRequested = false;
        var probe = agent.probe(config);
        List<CoverageLoopRoundSummary> rounds = new ArrayList<>();
        List<AgentRoundResult> agentRounds = new ArrayList<>();
        if (probe.status != AgentExecutionStatus.passed || stopRequested) {
            CoverageLoopStopReason reason = stopRequested ? CoverageLoopStopReason.aborted
                    : probe.status == AgentExecutionStatus.aborted ? CoverageLoopStopReason.aborted
                    : CoverageLoopStopReason.agent_unavailable;
            CoverageLoopResult result = new CoverageLoopResult();
            result.probe = probe;
            result.rounds = rounds;
            result.agentRounds = agentRounds;
            result.latest = null;
            result.stopReason = reason;
            result.message = stopMessage(reason, null, config.agent.maxRounds);
            return result;
        }

        MavenRunResult latest = maven.run(config, null, scopedOptions());
        rounds.add(roundSummary(latest));
        onRound.accept(latest);
        String previousFailureSignature = "";
        int sameFailureCount = 0;
        while (true) {
            CoverageLoopStopReason stopReason = null;
            if (stopRequested || latest.tests.status == TestExecutionStatus.aborted) {
                stopReason = CoverageLoopStopReason.aborted;
            }
            if(stopReason==null&&"dependency-resolution".equals(latest.tests.failureKind))stopReason=CoverageLoopStopReason.maven_failed;
            String promptMode = "coverage";
            if (stopReason == null && (latest.exitCode == null || latest.exitCode != 0
                    || latest.tests.status == TestExecutionStatus.test_failed)) {
                promptMode = "repair";
                String signature = mavenFailureSignature(latest);
                if (signature.equals(previousFailureSignature)) {
                    sameFailureCount += 1;
                } else {
                    previousFailureSignature = signature;
                    sameFailureCount = 1;
                }
                if (sameFailureCount >= config.agent.maxSameFailures) {
                    stopReason = CoverageLoopStopReason.repeated_maven_failure;
                }
            } else if (stopReason == null) {
                previousFailureSignature = "";
                sameFailureCount = 0;
                if (!canCollectCoverage(latest)) stopReason = CoverageLoopStopReason.invalid_report;
                else if (hasVerifiedCoverage(latest) && latest.groups.pending.isEmpty()) stopReason = CoverageLoopStopReason.target_reached;
            }
            if (stopReason == null && latest.round >= config.agent.maxRounds) {
                stopReason = CoverageLoopStopReason.max_rounds;
            }
            if (stopReason != null) {
                CoverageLoopResult result = new CoverageLoopResult();
                result.probe = probe;
                result.rounds = rounds;
                result.agentRounds = agentRounds;
                result.latest = latest;
                result.stopReason = stopReason;
                result.message = stopMessage(stopReason, latest,
                        stopReason == CoverageLoopStopReason.repeated_maven_failure
                                ? config.agent.maxSameFailures : config.agent.maxRounds);
                return result;
            }

            AgentRoundResult agentResult = agent.runRound(config, latest, promptMode);
            agentRounds.add(agentResult);
            onAgent.accept(agentResult);
            if (agentResult.status != AgentExecutionStatus.passed || stopRequested) {
                CoverageLoopStopReason reason = agentResult.status == AgentExecutionStatus.aborted || stopRequested
                        ? CoverageLoopStopReason.aborted : CoverageLoopStopReason.agent_failed;
                CoverageLoopResult result = new CoverageLoopResult();
                result.probe = probe;
                result.rounds = rounds;
                result.agentRounds = agentRounds;
                result.latest = latest;
                result.stopReason = reason;
                result.message = stopMessage(reason, latest, config.agent.maxRounds);
                return result;
            }

            latest = maven.run(config, latest.runId, scopedOptions());
            rounds.add(roundSummary(latest));
        onRound.accept(latest);
        }
    }

    public static boolean canCollectCoverage(MavenRunResult result) {
        return result!=null&&!result.coverage.isEmpty()
                &&result.coverage.stream().mapToInt(m->m.classCount).sum()>0
                &&result.coverage.stream().allMatch(m -> "jacoco".equals(m.source)||result.noTestModules.contains(m.modulePath));
    }

    public static boolean hasVerifiedCoverage(MavenRunResult result) {
        return result != null && !result.coverage.isEmpty()
                && result.coverage.stream().allMatch(m -> "jacoco".equals(m.source))
                && result.coverage.stream().mapToInt(m -> m.classCount).sum() > 0;
    }

    public boolean stop() {
        stopRequested = true;
        boolean mavenStopped = maven.stop();
        boolean agentStopped = agent.stop();
        return mavenStopped || agentStopped || stopRequested;
    }
    private static MavenRunner.RunOptions scopedOptions() {
        MavenRunner.RunOptions options=new MavenRunner.RunOptions(); options.scopeTests=true; options.continueOnTestFailure=true; return options;
    }

}
