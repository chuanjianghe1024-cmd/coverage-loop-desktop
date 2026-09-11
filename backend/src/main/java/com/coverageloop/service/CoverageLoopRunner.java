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
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import com.coverageloop.model.AgentOptions;

/** 自动补测循环状态机，与 Electron 版 coverage-loop-runner.ts 一致 */
public class CoverageLoopRunner {

    private volatile boolean stopRequested = false;
    private volatile boolean stopAfterRoundRequested = false;
    private final Object retryMonitor = new Object();
    private final java.util.function.Consumer<RecoveryNotice> onRecovery;

    public record RecoveryNotice(String runId, int round, String message, int attempt, String retryAt) {}

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
        this(maven, agent, onRound, onAgent, notice -> {});
    }

    public CoverageLoopRunner(MavenRunner maven, AgentRunner agent,
                              java.util.function.Consumer<MavenRunResult> onRound,
                              java.util.function.Consumer<AgentRoundResult> onAgent,
                              java.util.function.Consumer<RecoveryNotice> onRecovery) {
        this.onRecovery = onRecovery;
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
            case after_round:
                return "已按要求在当前轮验证结束后停止；已有代码和执行记录已保留";
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
        String logText = result.logPath == null ? null : Fs.readStringQuiet(result.logPath);
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

    /** Each Agent attempt is followed by a fresh verification, including partial work on timeout. */
    public CoverageLoopResult run(ProjectConfig config) throws Exception {
        if (!config.agent.enabled) throw new IllegalArgumentException("请先启用 Hermes 或 OpenCode Agent");
        if (config.selectedModulePaths == null || config.selectedModulePaths.isEmpty()) {
            throw new IllegalArgumentException("请至少选择一个模块");
        }
        // This runner belongs to one task. Do not reset a stop submitted before the worker starts.
        CoverageLoopResult result = new CoverageLoopResult();
        int probeFailures = 0;
        while (true) {
            if (shouldStop()) return finish(result, config, requestedStop());
            result.probe = agent.probe(config);
            if (stopRequested || result.probe.status == AgentExecutionStatus.aborted)
                return finish(result, config, CoverageLoopStopReason.aborted);
            if (stopAfterRoundRequested) return finish(result, config, CoverageLoopStopReason.after_round);
            if (result.probe.status == AgentExecutionStatus.passed) break;
            probeFailures++;
            if (!config.agent.runUntilTarget && (!AgentFailure.isTransient(result.probe.failureKind)
                    || probeFailures >= config.agent.maxSameFailures))
                return finish(result, config, CoverageLoopStopReason.agent_unavailable);
            if (!retry(config, result, "Agent 探活未成功，等待服务恢复后重新检查", probeFailures))
                return finish(result, config, requestedStop());
        }

        if (shouldStop()) return finish(result, config, requestedStop());
        validateRound(config, result);
        String previousFailureSignature = "";
        int sameFailureCount = 0, agentFailures = 0, validationFailures = 0;
        String agentRetryReason = null;
        while (true) {
            MavenRunResult latest = result.latest;
            if (stopRequested || latest.tests.status == TestExecutionStatus.aborted)
                return finish(result, config, CoverageLoopStopReason.aborted);
            boolean buildPassed = Objects.equals(latest.exitCode, 0)
                    && latest.tests.status != TestExecutionStatus.test_failed
                    && latest.tests.status != TestExecutionStatus.build_failed;
            if (buildPassed && hasVerifiedCoverage(latest) && latest.groups.pending.isEmpty())
                return finish(result, config, CoverageLoopStopReason.target_reached);
            if (stopAfterRoundRequested) return finish(result, config, CoverageLoopStopReason.after_round);
            if (!config.agent.runUntilTarget && latest.round >= config.agent.maxRounds)
                return finish(result, config, CoverageLoopStopReason.max_rounds);

            boolean dependencyFailure = "dependency-resolution".equals(latest.tests.failureKind);
            if (dependencyFailure || (buildPassed && !canCollectCoverage(latest))) {
                if (!config.agent.runUntilTarget) return finish(result, config, dependencyFailure
                        ? CoverageLoopStopReason.maven_failed : CoverageLoopStopReason.invalid_report);
                String reason = dependencyFailure ? "依赖解析失败，保留待评估状态并重试构建"
                        : "没有有效覆盖率报告，等待后重新验证";
                // An unavailable dependency/report is never handed to the Agent as missing test coverage.
                if (!retry(config, result, reason, ++validationFailures)) return finish(result, config, requestedStop());
                validateRound(config, result);
                continue;
            }
            validationFailures = 0;
            String promptMode = buildPassed ? "coverage" : "repair";
            if (!buildPassed) {
                String signature = mavenFailureSignature(latest);
                sameFailureCount = signature.equals(previousFailureSignature) ? sameFailureCount + 1 : 1;
                previousFailureSignature = signature;
                if (!config.agent.runUntilTarget && sameFailureCount >= config.agent.maxSameFailures)
                    return finish(result, config, CoverageLoopStopReason.repeated_maven_failure);
            } else {
                previousFailureSignature = "";
                sameFailureCount = 0;
            }
            if (agentRetryReason != null) {
                if (!retry(config, result, agentRetryReason, agentFailures)) return finish(result, config, requestedStop());
                agentRetryReason = null;
            } else if (sameFailureCount >= config.agent.maxSameFailures) {
                if (!retry(config, result, "同一测试/编译问题重复出现，延长等待后继续修复",
                        sameFailureCount - config.agent.maxSameFailures + 1)) return finish(result, config, requestedStop());
            }
            if (shouldStop()) return finish(result, config, requestedStop());
            AgentRoundResult attempt = agent.runRound(config, latest, promptMode);
            result.agentRounds.add(attempt);
            onAgent.accept(attempt);
            if (stopRequested || attempt.status == AgentExecutionStatus.aborted)
                return finish(result, config, CoverageLoopStopReason.aborted);
            if (attempt.status != AgentExecutionStatus.passed) {
                if (!config.agent.runUntilTarget && !AgentFailure.isTransient(attempt.failureKind)
                        && attempt.status != AgentExecutionStatus.timed_out)
                    return finish(result, config, CoverageLoopStopReason.agent_failed);
                agentFailures++;
                agentRetryReason = AgentFailure.description(attempt.failureKind);
                announce(result, agentRetryReason + "；保留已修改测试，进入新一轮验证", agentFailures, null);
            } else {
                agentFailures = 0;
            }
            // Graceful stop includes verification of this attempt, even when it timed out without a marker.
            validateRound(config, result);
        }
    }

    private void validateRound(ProjectConfig config, CoverageLoopResult result) throws Exception {
        if (stopRequested) throw new CancellationException("任务已停止");
        MavenRunner.RunOptions options = scopedOptions();
        MavenRunResult previous = result.latest;
        options.forcePreInstall = previous != null && previous.preInstall.attempted
                && !Objects.equals(previous.preInstall.exitCode, 0);
        result.latest = maven.run(config, previous == null ? null : previous.runId, options);
        result.rounds.add(roundSummary(result.latest));
        onRound.accept(result.latest);
    }

    private CoverageLoopResult finish(CoverageLoopResult result, ProjectConfig config, CoverageLoopStopReason reason) {
        result.stopReason = reason;
        result.message = stopMessage(reason, result.latest, reason == CoverageLoopStopReason.repeated_maven_failure
                ? config.agent.maxSameFailures : config.agent.maxRounds);
        return result;
    }

    public static int retryDelaySeconds(AgentOptions options, int attempt) {
        long base = Math.max(1, options.retryDelaySeconds);
        long cap = Math.max(base, options.maxRetryDelaySeconds);
        return (int)Math.min(cap, base * (1L << Math.min(30, Math.max(0, attempt - 1))));
    }

    private boolean retry(ProjectConfig config, CoverageLoopResult result, String reason, int attempt) throws InterruptedException {
        if (shouldStop()) return false;
        int seconds = retryDelaySeconds(config.agent, attempt);
        String retryAt = Instant.now().plusSeconds(seconds).toString();
        announce(result, reason + "；第 " + attempt + " 次恢复等待，" + seconds + " 秒后重试", attempt, retryAt);
        return awaitRetry(seconds * 1000L) && !shouldStop();
    }

    private void announce(CoverageLoopResult result, String message, int attempt, String retryAt) {
        MavenRunResult latest = result.latest;
        RecoveryNotice notice = new RecoveryNotice(latest == null ? null : latest.runId,
                latest == null ? 0 : latest.round, message, attempt, retryAt);
        String path = latest != null ? latest.logPath : result.probe == null ? null : result.probe.logPath;
        if (path != null) Fs.appendString(path, "[" + Instant.now() + "] [LOOP_RECOVERY] " + message
                + (retryAt == null ? "" : " retryAt=" + retryAt) + "\n");
        onRecovery.accept(notice);
    }

    /** Interruptible backoff; stop signals wake it immediately. Overridable for deterministic tests. */
    protected boolean awaitRetry(long milliseconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(milliseconds);
        synchronized (retryMonitor) {
            while (!shouldStop()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) return true;
                TimeUnit.NANOSECONDS.timedWait(retryMonitor, remaining);
            }
        }
        return false;
    }

    private boolean shouldStop() { return stopRequested || stopAfterRoundRequested; }
    private CoverageLoopStopReason requestedStop() {
        return stopRequested ? CoverageLoopStopReason.aborted : CoverageLoopStopReason.after_round;
    }

    public void stopAfterRound() {
        stopAfterRoundRequested = true;
        synchronized (retryMonitor) { retryMonitor.notifyAll(); }
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
        synchronized (retryMonitor) { retryMonitor.notifyAll(); }
        boolean mavenStopped = maven.stop();
        boolean agentStopped = agent.stop();
        return mavenStopped || agentStopped || stopRequested;
    }
    private static MavenRunner.RunOptions scopedOptions() {
        MavenRunner.RunOptions options=new MavenRunner.RunOptions(); options.scopeTests=true; options.continueOnTestFailure=true; return options;
    }

}
