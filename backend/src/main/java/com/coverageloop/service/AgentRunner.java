package com.coverageloop.service;

import com.coverageloop.model.AgentExecutionStatus;
import com.coverageloop.model.AgentOptions;
import com.coverageloop.model.AgentProbeResult;
import com.coverageloop.model.AgentRoundResult;
import com.coverageloop.model.MavenOutputEvent;
import com.coverageloop.model.MavenProgressEvent;
import com.coverageloop.model.MavenProgressStage;
import com.coverageloop.model.MavenRunResult;
import com.coverageloop.model.ProjectConfig;
import com.coverageloop.util.Fs;
import com.coverageloop.util.Names;
import com.coverageloop.util.Proc;
import com.coverageloop.util.Text;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Agent（Hermes / OpenCode）执行器，与 Electron 版 agent-runner.ts 一致 */
public class AgentRunner {

    public interface Sink {
        void output(MavenOutputEvent event);

        void progress(MavenProgressEvent event);
    }

    private final java.util.function.BooleanSupplier cancelled;
    private final MavenRunner.OutputSink sink;
    private final AtomicReference<Process> child = new AtomicReference<>();
    private volatile boolean abortRequested = false;

    public AgentRunner(MavenRunner.OutputSink sink) {
        this(sink, () -> false);
    }

    public AgentRunner(MavenRunner.OutputSink sink, java.util.function.BooleanSupplier cancelled) {
        this.cancelled = cancelled;
        this.sink = sink;
    }

    public boolean isRunning() {
        return child.get() != null;
    }

    public static boolean isSuccessfulProbeResponse(String value) {
        for (String line : Text.stripAnsi(value).split("\\r?\\n")) {
            if (line.trim().toUpperCase().equals("OK")) return true;
        }
        return false;
    }

    public static boolean hasBatchCompleteMarker(String value) {
        for (String line : Text.stripAnsi(value).split("\\r?\\n")) {
            if (line.trim().toUpperCase().equals("BATCH_COMPLETE")) return true;
        }
        return false;
    }

    public static class AgentCommand {
        public String executable;
        public List<String> args = new ArrayList<>();
    }

    public static AgentCommand buildAgentCommand(AgentOptions options, String prompt, String mode,
                                                 String workingDirectory) {
        String executable = options.executable == null || options.executable.trim().isEmpty()
                ? options.provider : options.executable.trim();
        AgentCommand command = new AgentCommand();
        command.executable = executable;
        if ("hermes".equals(options.provider)) {
            List<String> overrides = new ArrayList<>();
            if (options.hermesProvider != null && !options.hermesProvider.trim().isEmpty()) {
                overrides.add("--provider");
                overrides.add(options.hermesProvider.trim());
            }
            if (options.model != null && !options.model.trim().isEmpty()) {
                overrides.add("--model");
                overrides.add(options.model.trim());
            }
            overrides.addAll(options.extraArgs);
            List<String> args = new ArrayList<>();
            if ("probe".equals(mode)) {
                args.add("-z");
                args.add(prompt);
            } else {
                args.add("chat");
                if (options.autoApprove) args.add("--yolo");
                args.add("--verbose");
                args.addAll(overrides);
                args.add("-q");
                args.add(prompt);
            }
            command.args = args;
            return command;
        }
        // opencode
        List<String> args = new ArrayList<>();
        args.add("run");
        if ("work".equals(mode) && options.autoApprove) args.add("--auto");
        args.add("--format");
        args.add("work".equals(mode)?"json":"default");
        if (workingDirectory != null && !workingDirectory.isEmpty()) {
            args.add("--dir");
            args.add(workingDirectory);
        }
        if (options.model != null && !options.model.trim().isEmpty()) {
            args.add("--model");
            args.add(options.model.trim());
        }
        if (options.opencodeAgent != null && !options.opencodeAgent.trim().isEmpty()) {
            args.add("--agent");
            args.add(options.opencodeAgent.trim());
        }
        if (options.opencodeAttach != null && !options.opencodeAttach.trim().isEmpty()) {
            args.add("--attach");
            args.add(options.opencodeAttach.trim());
        }
        args.addAll(options.extraArgs);
        args.add(prompt);
        command.args = args;
        return command;
    }

    private static String quoted(String value) {
        return value.matches(".*[\\s\"'].*") ? "\"" + value.replace("\"", "\\\"") + "\"" : value;
    }

    private static String commandText(MavenRunResult result) {
        List<String> parts = new ArrayList<>();
        parts.add(result.command.executable);
        parts.addAll(result.command.args);
        return String.join(" ", parts.stream().map(AgentRunner::quoted).toList());
    }

    private static String projectRoot(ProjectConfig config) {
        return new File(config.rootPomPath).getAbsoluteFile().getParent();
    }

    private static String moduleRootsText(ProjectConfig config) {
        List<String> lines = new ArrayList<>();
        for (String modulePath : config.selectedModulePaths) {
            lines.add(".".equals(modulePath) ? projectRoot(config)
                    : new File(projectRoot(config), modulePath).getPath());
        }
        return String.join("\n", lines);
    }

    private static String productionRule(ProjectConfig config) {
        if (config.agent.allowProductionChanges) {
            return "允许目标模块内有限的可测试性重构，但不得改变公共 API、业务行为、异常类型、事务边界、Maven 配置或 JaCoCo 配置。";
        }
        return "只允许修改目标模块 src/test 下的测试代码和测试资源，禁止修改生产代码。";
    }

    private static String renderTemplate(String template, Map<String, String> values) {
        String rendered = template;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            rendered = rendered.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        return rendered.trim();
    }

    private static String targetClassLines(ProjectConfig config, MavenRunResult result) {
        List<String> lines = new ArrayList<>();
        int count = Math.min(result.groups.pending.size(), config.agent.batchSize);
        for (int i = 0; i < count; i++) {
            var item = result.groups.pending.get(i);
            lines.add(String.format("%d. [%s] %s（当前行覆盖率 %s%%）", i + 1,
                    item.modulePath, item.qualifiedName,
                    item.currentLineCoverage == null ? 0 : item.currentLineCoverage));
        }
        return lines.isEmpty() ? "没有待补充类。" : String.join("\n", lines);
    }

    public static class BuiltPrompt {
        public String prompt;
        public int selectedClassCount;
    }

    public static BuiltPrompt buildAgentPrompt(ProjectConfig config, MavenRunResult result, String mode,
                                               String failedClassesPath) {
        BuiltPrompt built = new BuiltPrompt();
        built.selectedClassCount = "coverage".equals(mode)
                ? Math.min(result.groups.pending.size(), config.agent.batchSize) : 0;
        String template = "coverage".equals(mode)
                ? config.agent.coveragePromptTemplate : config.agent.repairPromptTemplate;
        String roundLabel = Names.roundLabel(result.round);
        Map<String, String> values = new HashMap<>();
        values.put("round", String.valueOf(result.round));
        values.put("projectRoot", projectRoot(config));
        values.put("moduleRoots", moduleRootsText(config));
        values.put("rootPom", new File(config.rootPomPath).getAbsolutePath());
        values.put("mavenLog", result.logPath);
        values.put("surefireReports", result.tests.reportArchivePath != null
                ? result.tests.reportArchivePath : "本轮没有可归档的新鲜 Surefire 报告，请以完整 Maven 日志为准。");
        values.put("coverageSnapshot", result.coverageSnapshotPath);
        values.put("coverageGate", new File(result.runDirectory,
                "round-" + roundLabel + "-coverage-gate.txt").getPath());
        values.put("failedClasses", failedClassesPath != null
                ? failedClassesPath : "本轮为 Maven/测试修复，不使用覆盖率待补类清单。");
        values.put("testCommand", commandText(result));
        values.put("targetClasses", targetClassLines(config, result));
        values.put("batchSize", String.valueOf(config.agent.batchSize));
        values.put("productionRule", productionRule(config));
        built.prompt = renderTemplate(template, values);
        return built;
    }

    private static String writeFailedClasses(MavenRunResult result) {
        String path = new File(result.runDirectory,
                "round-" + Names.roundLabel(result.round) + "-failed-classes.txt").getPath();
        List<String> lines = new ArrayList<>();
        int index = 1;
        for (var item : result.groups.pending) {
            int total = item.coveredLines + item.missedLines;
            lines.add(String.format("%d. [%s] %s | LINE=%s%% | covered=%d/%d", index++,
                    item.modulePath, item.qualifiedName,
                    item.currentLineCoverage == null ? 0 : item.currentLineCoverage,
                    item.coveredLines, total));
        }
        Fs.writeString(path, lines.isEmpty() ? "" : String.join("\n", lines) + "\n");
        return path;
    }

    private static class TestFileState {
        long size;
        long modifiedAtMs;
        String displayPath;
    }

    private static void walkTestFiles(File directory, File root, Map<String, TestFileState> snapshot) {
        File[] entries = directory.listFiles();
        if (entries == null) return;
        for (File entry : entries) {
            if (entry.isDirectory()) {
                walkTestFiles(entry, root, snapshot);
                continue;
            }
            if (!entry.isFile()) continue;
            TestFileState state = new TestFileState();
            state.size = Fs.size(entry.getPath());
            state.modifiedAtMs = Fs.lastModifiedMs(entry.getPath());
            String relative = root.toPath().toAbsolutePath().normalize()
                    .relativize(entry.toPath().toAbsolutePath().normalize())
                    .toString().replace(File.separatorChar, '/');
            state.displayPath = relative;
            snapshot.put(entry.getAbsolutePath(), state);
        }
    }

    private static Map<String, TestFileState> snapshotTestFiles(ProjectConfig config) {
        File root = new File(projectRoot(config));
        Map<String, TestFileState> snapshot = new HashMap<>();
        for (String modulePath : config.selectedModulePaths) {
            File moduleRoot = ".".equals(modulePath) ? root : new File(root, modulePath);
            walkTestFiles(new File(moduleRoot, "src/test"), root, snapshot);
        }
        return snapshot;
    }

    private static List<String> writeChangedTests(Map<String, TestFileState> before,
                                                  Map<String, TestFileState> after, String path) {
        List<String> changed = new ArrayList<>();
        java.util.Set<String> files = new java.util.TreeSet<>();
        files.addAll(before.keySet());
        files.addAll(after.keySet());
        for (String file : files) {
            TestFileState oldState = before.get(file);
            TestFileState newState = after.get(file);
            String display = newState != null ? newState.displayPath : oldState != null ? oldState.displayPath : file;
            if (oldState == null && newState != null) {
                changed.add("ADDED    " + display);
            } else if (oldState != null && newState == null) {
                changed.add("DELETED  " + display);
            } else if (oldState != null && newState != null
                    && (oldState.size != newState.size || oldState.modifiedAtMs != newState.modifiedAtMs)) {
                changed.add("MODIFIED " + display);
            }
        }
        Fs.writeString(path, changed.isEmpty() ? "NO_TEST_FILE_CHANGES\n" : String.join("\n", changed) + "\n");
        return changed;
    }

    private static class ProcessResult {
        AgentExecutionStatus status;
        Integer exitCode;
        String signal;
        String stdout = "";
        String stderr = "";
        String startedAt;
        String finishedAt;
    }

    private ProcessResult execute(AgentCommand command, String prompt, String workingDirectory, String logPath,
                                  long timeoutMs, int heartbeatSeconds, String runId, int round, String phase) {
        if (child.get() != null) throw new IllegalStateException("已有 Agent 任务正在运行");
        Fs.mkdirs(new File(logPath).getParent());
        StringBuffer log = new StringBuffer();
        String startedAt = Instant.now().toString();
        long startedAtMs = System.currentTimeMillis();
        AtomicReference<Long> lastOutputAtMs = new AtomicReference<>(startedAtMs);
        AtomicBoolean timedOut = new AtomicBoolean(false);
        if (cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException("任务已停止");
        abortRequested = false;
        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();

        log.append("[").append(startedAt).append("] provider-command: ").append(command.executable).append(" ")
                .append(String.join(" ", command.args.stream().filter(arg -> !arg.equals(prompt)).toList()))
                .append(" <prompt>\n");
        log.append("[").append(startedAt).append("] prompt:\n").append(prompt).append("\n\n");

        String taskText = "probe".equals(phase)
                ? "Agent 探活：" + command.executable + "\n"
                : "repair".equals(phase)
                        ? "Agent 第 " + round + " 轮开始修复失败用例\n"
                        : "Agent 第 " + round + " 轮开始补充用例\n";
        emit(runId, round, "system", taskText);
        emitProgress(runId, round, "probe".equals(phase) ? MavenProgressStage.agent_probing : MavenProgressStage.agent_running,
                "probe".equals(phase) ? 2 : 50,
                "probe".equals(phase) ? "正在验证 Agent，等待回复 OK"
                        : "repair".equals(phase) ? "Agent 正在修复第 " + round + " 轮 Maven/测试失败"
                        : "Agent 正在处理第 " + round + " 轮待补充类");

        Map<String, String> env = new HashMap<>();
        env.put("NO_COLOR", "1");
        env.put("FORCE_COLOR", "0");
        env.put("HERMES_DISABLE_LAZY_INSTALLS", "1");
        env.put("OPENCODE_DISABLE_AUTOUPDATE", "true");
        List<String> cmdLine = Proc.commandLine(command.executable, command.args);
        ProcessBuilder builder = new ProcessBuilder(cmdLine);
        builder.directory(new File(workingDirectory));
        builder.environment().putAll(env);
        builder.redirectErrorStream(false);
        Process process;
        try {
            process = builder.start();
        } catch (IOException error) {
            ProcessResult failed = new ProcessResult();
            failed.status = AgentExecutionStatus.failed;
            failed.exitCode = null;
            failed.signal = error.getMessage();
            failed.startedAt = startedAt;
            failed.finishedAt = Instant.now().toString();
            log.append("[").append(failed.finishedAt).append("] [error] ").append(error.getMessage()).append("\n");
            Fs.writeString(logPath, log.toString());
            emit(runId, round, "system", "Agent 启动失败：" + error.getMessage() + "\n");
            return failed;
        }
        child.set(process);
        if (cancelled.getAsBoolean() || abortRequested) Proc.killTree(process);
        long pid = process.pid();
        log.append("[").append(Instant.now()).append("] [PROCESS_STARTED] pid=").append(pid).append("\n");

        Thread stdoutThread = pump(process.getInputStream(), chunk -> {
            String text = chunk;
            lastOutputAtMs.set(System.currentTimeMillis());
            synchronized (stdout) {
                String tail = stdout.append(text).toString();
                stdout.setLength(0);
                stdout.append(tail.length() > 2_000_000 ? tail.substring(tail.length() - 2_000_000) : tail);
            }
            log.append("[").append(Instant.now()).append("] [stdout] ").append(text);
            emit(runId, round, "stdout", "[Agent] " + text);
        });
        Thread stderrThread = pump(process.getErrorStream(), chunk -> {
            String text = chunk;
            lastOutputAtMs.set(System.currentTimeMillis());
            synchronized (stderr) {
                String tail = stderr.append(text).toString();
                stderr.setLength(0);
                stderr.append(tail.length() > 2_000_000 ? tail.substring(tail.length() - 2_000_000) : tail);
            }
            log.append("[").append(Instant.now()).append("] [stderr] ").append(text);
            emit(runId, round, "stderr", "[Agent] " + text);
        });

        int heartbeatMs = Math.max(1, heartbeatSeconds) * 1000;
        Thread heartbeat = new Thread(() -> {
            try {
                while (true) {
                    Thread.sleep(heartbeatMs);
                    if (child.get() != null && process.isAlive()) {
                        long now = System.currentTimeMillis();
                        String text = "[ALIVE] pid=" + pid + " elapsed=" + Text.elapsedText(now - startedAtMs)
                                + " no_output=" + Text.elapsedText(now - lastOutputAtMs.get())
                                + " time=" + Instant.now() + "\n";
                        log.append(text);
                        emit(runId, round, "system", "[Agent] " + text);
                    }
                }
            } catch (InterruptedException error) {
                // 进程结束时停止心跳
            }
        });
        heartbeat.setDaemon(true);
        heartbeat.start();

        Thread timeoutWatcher = new Thread(() -> {
            try {
                Thread.sleep(timeoutMs);
                if (child.get() == process && process.isAlive()) {
                    timedOut.set(true);
                    Proc.killTree(process);
                }
            } catch (InterruptedException error) {
                // 进程结束时取消超时监视
            }
        });
        timeoutWatcher.setDaemon(true);
        timeoutWatcher.start();

        ProcessExecution processResult;
        try {
            int exitCode = process.waitFor();
            processResult = new ProcessExecution(exitCode, null);
        } catch (InterruptedException error) {
            Proc.killTree(process);
            Thread.currentThread().interrupt();
            processResult = new ProcessExecution(null, null);
        }
        heartbeat.interrupt();
        timeoutWatcher.interrupt();
        try {
            stdoutThread.join();
            stderrThread.join();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
        child.set(null);
        String finishedAt = Instant.now().toString();

        AgentExecutionStatus status = (abortRequested || cancelled.getAsBoolean()) ? AgentExecutionStatus.aborted
                : timedOut.get() ? AgentExecutionStatus.timed_out
                : processResult.exitCode != null && processResult.exitCode == 0
                        ? AgentExecutionStatus.passed : AgentExecutionStatus.failed;
        log.append("[").append(finishedAt).append("] [PROCESS_EXITED] status=").append(status.name())
                .append(" exitCode=").append(processResult.exitCode == null ? "null" : processResult.exitCode)
                .append(" elapsed=").append(Text.elapsedText(System.currentTimeMillis() - startedAtMs)).append("\n");
        Fs.writeString(logPath, log.toString());

        ProcessResult result = new ProcessResult();
        result.status = status;
        result.exitCode = processResult.exitCode;
        result.signal = processResult.signal;
        result.stdout = stdout.toString();
        result.stderr = stderr.toString();
        result.startedAt = startedAt;
        result.finishedAt = finishedAt;
        return result;
    }

    private static class ProcessExecution {
        final Integer exitCode;
        final String signal;

        ProcessExecution(Integer exitCode, String signal) {
            this.exitCode = exitCode;
            this.signal = signal;
        }
    }

    private Thread pump(java.io.InputStream stream, java.util.function.Consumer<String> consumer) {
        Thread thread = new Thread(() -> {
            char[] buffer = new char[8192];
            try (var reader = new java.io.InputStreamReader(stream, StandardCharsets.UTF_8)) {
                int read;
                while ((read = reader.read(buffer)) != -1) {
                    if (read > 0) consumer.accept(new String(buffer, 0, read));
                }
            } catch (IOException error) {
                // 进程退出后流关闭，正常结束
            }
        });
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private void emit(String runId, int round, String stream, String text) {
        MavenOutputEvent event = new MavenOutputEvent();
        event.stream = stream;
        event.text = text;
        event.timestamp = Instant.now().toString();
        event.runId = runId;
        event.round = round;
        sink.output(event);
    }

    private void emitProgress(String runId, int round, MavenProgressStage stage, int percent, String message) {
        MavenProgressEvent event = new MavenProgressEvent();
        event.runId = runId;
        event.round = round;
        event.stage = stage;
        event.percent = percent;
        event.message = message;
        event.timestamp = Instant.now().toString();
        sink.progress(event);
    }

    public AgentProbeResult probe(ProjectConfig config) {
        String workingDirectory = projectRoot(config);
        String logPath = new File(new File(new File(workingDirectory, ".coverage-loop"), "runs"),
                Names.normalizedId(config.id, "default") + File.separator + "agent-probes"
                        + File.separator + Names.timestampId() + ".log").getPath();
        String prompt = "只回复 OK，不要解释，不要调用任何工具。";
        AgentCommand command = buildAgentCommand(config.agent, prompt, "probe", workingDirectory);
        ProcessResult result = execute(command, prompt, workingDirectory, logPath,
                120_000, config.agent.heartbeatSeconds, "agent-probe", 0, "probe");
        String response = Text.stripAnsi((result.stdout + "\n" + result.stderr)).trim();
        if (response.length() > 8_000) response = response.substring(response.length() - 8_000);
        AgentExecutionStatus status = result.status == AgentExecutionStatus.passed
                && isSuccessfulProbeResponse(result.stdout)
                ? AgentExecutionStatus.passed
                : result.status == AgentExecutionStatus.passed ? AgentExecutionStatus.failed : result.status;
        Fs.appendString(logPath, "[" + Instant.now() + "] probe-validation="
                + (status == AgentExecutionStatus.passed ? "received-OK" : "missing-OK") + "\n");
        emit(null, 0, "system", status == AgentExecutionStatus.passed
                ? "Agent 探活成功：收到 OK\n" : "Agent 探活失败：未收到独立的 OK 回复\n");

        AgentProbeResult probe = new AgentProbeResult();
        probe.status = status;
        probe.failureKind = AgentFailure.classify(status, result.signal, result.stdout + "\n" + result.stderr);
        probe.provider = config.agent.provider;
        probe.executable = command.executable;
        probe.exitCode = result.exitCode;
        probe.signal = result.signal;
        probe.response = response;
        probe.logPath = logPath;
        probe.startedAt = result.startedAt;
        probe.finishedAt = result.finishedAt;
        return probe;
    }

    protected long roundTimeoutMillis(AgentOptions options) {
        return (long) options.timeoutMinutes * 60_000;
    }

    public AgentRoundResult runRound(ProjectConfig config, MavenRunResult result, String mode) {
        String label = Names.roundLabel(result.round);
        String failedClassesPath = "coverage".equals(mode) ? writeFailedClasses(result) : null;
        BuiltPrompt built = buildAgentPrompt(config, result, mode, failedClassesPath);
        String workingDirectory = projectRoot(config);
        AgentCommand command = buildAgentCommand(config.agent, built.prompt, "work", workingDirectory);
        String logPath = new File(result.runDirectory, "round-" + label + "-agent.log").getPath();
        String promptPath = new File(result.runDirectory, "round-" + label + "-prompt.txt").getPath();
        String changedTestsPath = new File(result.runDirectory, "round-" + label + "-changed-tests.txt").getPath();
        Fs.writeString(promptPath, built.prompt + "\n");
        Map<String, TestFileState> before = snapshotTestFiles(config);
        ProcessResult execution = execute(command, built.prompt, workingDirectory, logPath,
                roundTimeoutMillis(config.agent), config.agent.heartbeatSeconds,
                result.runId, result.round, mode);
        Map<String, TestFileState> after = snapshotTestFiles(config);
        List<String> changedTestFiles = writeChangedTests(before, after, changedTestsPath);
        boolean completionMarkerSeen = hasBatchCompleteMarker(AgentSessions.readable(execution.stdout + "\n" + execution.stderr));
        Fs.appendString(logPath, "[" + Instant.now() + "] completion-marker="
                + (completionMarkerSeen ? "BATCH_COMPLETE" : "missing")
                + " changed-test-files=" + changedTestFiles.size() + "\n");
        emit(result.runId, result.round, "system", changedTestFiles.isEmpty()
                ? "Agent 未修改 src/test 下的文件；清单：" + changedTestsPath + "\n"
                : "Agent 修改了 " + changedTestFiles.size() + " 个测试文件；清单：" + changedTestsPath + "\n");

        AgentRoundResult round = new AgentRoundResult();
        round.status = execution.status;
        round.failureKind = AgentFailure.classify(execution.status, execution.signal, execution.stdout + "\n" + execution.stderr);
        if (round.failureKind != null) Fs.appendString(logPath, "[" + Instant.now() + "] failure-kind=" + round.failureKind + "\n");
        round.mode = mode;
        round.round = result.round;
        round.exitCode = execution.exitCode;
        round.signal = execution.signal;
        round.logPath = logPath;
        round.promptPath = promptPath;
        round.failedClassesPath = failedClassesPath;
        round.changedTestsPath = changedTestsPath;
        round.changedTestFiles = changedTestFiles;
        round.completionMarkerSeen = completionMarkerSeen;
        round.selectedClassCount = built.selectedClassCount;
        round.startedAt = execution.startedAt;
        round.finishedAt = execution.finishedAt;
        AgentSessions.persist(result,round,config,execution.stdout+"\n"+execution.stderr);
        return round;
    }

    public boolean stop() {
        Process process = child.get();
        if (process == null || !process.isAlive()) return false;
        abortRequested = true;
        Proc.killTree(process);
        return true;
    }
}
