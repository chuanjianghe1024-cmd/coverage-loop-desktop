package com.coverageloop.service;

import com.coverageloop.model.MavenCommandPreview;
import com.coverageloop.model.MavenOutputEvent;
import com.coverageloop.model.MavenProgressEvent;
import com.coverageloop.model.MavenProgressStage;
import com.coverageloop.model.MavenRunResult;
import com.coverageloop.model.ProjectConfig;
import com.coverageloop.model.TestExecutionResult;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Maven 执行器：命令解析、进程管理、流式输出、心跳与进度推断，与 Electron 版 maven-runner.ts 一致 */
public class MavenRunner {

    /** 输出回调（从后台线程触发） */
    public interface OutputSink {
        void output(MavenOutputEvent event);

        void progress(MavenProgressEvent event);
    }

    public static class RunnerPaths {
        public String resourcesPath;
        public String developmentRoot;
    }

    public static class RunOptions {
        public boolean fullProject;
        public boolean continueOnTestFailure;
    }

    private final java.util.function.BooleanSupplier cancelled;
    private volatile boolean stopRequested;
    private final RunnerPaths paths;
    private final OutputSink sink;
    private final AtomicReference<Process> child = new AtomicReference<>();

    public MavenRunner(RunnerPaths paths, OutputSink sink) {
        this(paths, sink, () -> false);
    }

    public MavenRunner(RunnerPaths paths, OutputSink sink, java.util.function.BooleanSupplier cancelled) {
        this.cancelled = cancelled;
        this.paths = paths;
        this.sink = sink;
    }

    public boolean isRunning() {
        return child.get() != null;
    }

    private static String executableName(String base) {
        return Proc.isWindows() ? base + ".cmd" : base;
    }

    private static String javaName() {
        return Proc.isWindows() ? "java.exe" : "java";
    }

    private static boolean hasSystemProperty(List<String> args, String property) {
        return args.stream().anyMatch(arg -> arg.equals("-D" + property) || arg.startsWith("-D" + property + "="));
    }

    private static List<String> withoutSystemProperties(List<String> args, List<String> properties) {
        return args.stream().filter(arg -> properties.stream().noneMatch(property ->
                arg.equals("-D" + property) || arg.startsWith("-D" + property + "="))).toList();
    }

    private static boolean hasThreadOption(List<String> args) {
        return args.stream().anyMatch(arg -> arg.equals("-T") || arg.equals("--threads")
                || arg.matches("^-T.+") || arg.startsWith("--threads="));
    }

    public MavenCommandPreview resolveMavenCommand(ProjectConfig config, RunOptions options) {
        String rootDirectory = new File(config.rootPomPath).getAbsoluteFile().getParent();
        String packagedMaven = new File(new File(paths.resourcesPath, "toolchain/maven"), "bin/" + executableName("mvn")).getPath();
        String developmentMaven = new File(new File(paths.developmentRoot, "resources/toolchain/maven"), "bin/" + executableName("mvn")).getPath();
        String bundledMaven = Fs.exists(packagedMaven) ? packagedMaven : developmentMaven;
        String wrapper = new File(rootDirectory, executableName("mvnw")).getPath();
        String configured = config.maven.executable == null || config.maven.executable.isBlank()
                ? "" : new File(config.maven.executable).getAbsolutePath();
        String environmentHome = firstNonEmpty(System.getenv("MAVEN_HOME"), System.getenv("M2_HOME"));
        String environmentMaven = environmentHome != null
                ? new File(new File(environmentHome, "bin"), executableName("mvn")).getPath()
                : executableName("mvn");

        String executable = environmentMaven;
        String source = "environment";
        if (config.maven.useBundledMaven && Fs.exists(bundledMaven)) {
            executable = bundledMaven;
            source = "bundled";
        } else if (!configured.isEmpty() && Fs.exists(configured)) {
            executable = configured;
            source = "configured";
        } else if (Fs.exists(wrapper)) {
            executable = wrapper;
            source = "wrapper";
        }

        String configuredJava = config.maven.javaHome == null || config.maven.javaHome.isBlank()
                ? "" : new File(config.maven.javaHome).getAbsolutePath();
        String javaHome = null;
        if (!configuredJava.isEmpty() && Fs.exists(new File(new File(configuredJava, "bin"), javaName()).getPath())) {
            javaHome = configuredJava;
        } else if (System.getenv("JAVA_HOME") != null) {
            javaHome = System.getenv("JAVA_HOME");
        }

        List<String> baseArgs = new ArrayList<>();
        baseArgs.add("--no-transfer-progress");
        baseArgs.add("-f");
        baseArgs.add(config.rootPomPath);
        if (config.maven.parallelThreads > 1 && !hasThreadOption(config.maven.extraArgs)) {
            baseArgs.add(0, "-T");
            baseArgs.add(1, String.valueOf(config.maven.parallelThreads));
        }
        if (config.maven.settingsPath != null && !config.maven.settingsPath.isBlank()) {
            baseArgs.add("-s");
            baseArgs.add(config.maven.settingsPath);
        }
        if (config.maven.localRepository != null && !config.maven.localRepository.isBlank()) {
            baseArgs.add("-Dmaven.repo.local=" + config.maven.localRepository);
        }
        if (config.maven.profiles != null && !config.maven.profiles.isEmpty()) {
            baseArgs.add("-P");
            baseArgs.add(String.join(",", config.maven.profiles));
        }
        if (!options.fullProject && config.selectedModulePaths != null && !config.selectedModulePaths.isEmpty()) {
            // 根模块（"."）不能作为 -pl 参数；过滤后为空表示单模块项目，直接构建整个根项目
            List<String> modulePaths = config.selectedModulePaths.stream()
                    .filter(path -> !".".equals(path)).toList();
            if (!modulePaths.isEmpty()) {
                baseArgs.add("-pl");
                baseArgs.add(String.join(",", modulePaths));
                baseArgs.add("-am");
            }
        }

        List<String> args = new ArrayList<>(baseArgs);
        if (!hasSystemProperty(config.maven.extraArgs, "failIfNoTests")) {
            args.add("-DfailIfNoTests=false");
        }
        if (!hasSystemProperty(config.maven.extraArgs, "surefire.failIfNoSpecifiedTests")) {
            args.add("-Dsurefire.failIfNoSpecifiedTests=false");
        }
        if (config.maven.testPattern != null && !config.maven.testPattern.trim().isEmpty()
                && !hasSystemProperty(config.maven.extraArgs, "test")) {
            args.add("-Dtest=" + config.maven.testPattern.trim());
        }
        List<String> executionProperties = List.of("skipTests", "maven.test.skip", "jacoco.skip", "maven.test.failure.ignore");
        List<String> coverageExtraArgs = options.continueOnTestFailure
                ? withoutSystemProperties(config.maven.extraArgs,
                        combine(executionProperties, List.of("maven.test.failure.ignore")))
                : withoutSystemProperties(config.maven.extraArgs, executionProperties);
        args.addAll(coverageExtraArgs);
        if (options.continueOnTestFailure) args.add("-Dmaven.test.failure.ignore=true");
        args.add("-DskipTests=false");
        args.add("-Dmaven.test.skip=false");
        args.add("-Djacoco.skip=false");
        String plugin = "org.jacoco:jacoco-maven-plugin:" + config.coverage.jacocoVersion;
        args.add(plugin + ":prepare-agent");
        args.add("test");
        args.add(plugin + ":report");

        List<String> preInstallArgs = null;
        if (config.maven.preInstall) {
            preInstallArgs = new ArrayList<>(baseArgs);
            preInstallArgs.addAll(withoutSystemProperties(config.maven.extraArgs,
                    List.of("skipTests", "maven.test.skip", "jacoco.skip", "maven.test.failure.ignore")));
            preInstallArgs.add("-DskipTests=true");
            preInstallArgs.add("-Djacoco.skip=true");
            preInstallArgs.add("install");
        }

        MavenCommandPreview preview = new MavenCommandPreview();
        preview.executable = executable;
        preview.args = args;
        preview.preInstallArgs = preInstallArgs;
        preview.workingDirectory = rootDirectory;
        preview.javaHome = javaHome;
        preview.source = source;
        return preview;
    }

    private static List<String> combine(List<String> first, List<String> second) {
        List<String> result = new ArrayList<>(first);
        result.addAll(second);
        return result;
    }

    private static String firstNonEmpty(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    /** 执行一轮 Maven（阻塞），返回完整结果 */
    public MavenRunResult run(ProjectConfig config, String sessionId, RunOptions options) throws Exception {
        if (child.get() != null) throw new IllegalStateException("已有 Maven 任务正在运行");
        if (cancelled.getAsBoolean()) throw new java.util.concurrent.CancellationException("任务已停止");
        stopRequested = false;
        if (!options.fullProject && (config.selectedModulePaths == null || config.selectedModulePaths.isEmpty())) {
            throw new IllegalArgumentException("请至少选择一个模块");
        }

        RunHistory.PreparedRound prepared = RunHistory.prepareRound(config, sessionId);
        MavenCommandPreview command = resolveMavenCommand(config, options);
        String startedAt = Instant.now().toString();

        StringBuilder logBuffer = new StringBuilder();
        AtomicReference<LogWriter> logWriterRef = new AtomicReference<>();
        final int[] progress = {0};
        final String[] phase = {"coverage"};
        final AtomicLong lastProcessOutputAt = new AtomicLong(System.currentTimeMillis());

        output(prepared, "system", "执行记录：" + prepared.runId + "，第 " + prepared.round + " 轮\n");
        output(prepared, "system", "完整日志：" + prepared.logPath + "\n");
        updateProgress(prepared, 3, MavenProgressStage.preparing, "正在准备 Maven 命令与日志目录");

        ProcessExecution execution;
        boolean shouldPreInstall = prepared.round == 1 && command.preInstallArgs != null;
        Integer preInstallExitCode = null;
        try {
            if (shouldPreInstall && command.preInstallArgs != null) {
                phase[0] = "pre-install";
                output(prepared, "system", "预构建（跳过测试）：" + command.executable + " "
                        + String.join(" ", command.preInstallArgs) + "\n");
                updateProgress(prepared, 5, MavenProgressStage.installing, "正在执行首轮预构建：skip tests install");
                ProcessExecution preInstallResult = execute(prepared, config, command, command.preInstallArgs,
                        lastProcessOutputAt, logBuffer, logWriterRef, phase);
                preInstallExitCode = preInstallResult.exitCode;
                output(prepared, "system", "预构建已结束，退出码：" + preInstallResult.exitCode + "\n");
                if (preInstallResult.exitCode != 0) {
                    execution = preInstallResult;
                } else {
                    phase[0] = "coverage";
                    output(prepared, "system", "覆盖率执行：" + command.executable + " " + String.join(" ", command.args) + "\n");
                    updateProgress(prepared, 35, MavenProgressStage.resolving, "预构建完成，开始测试与覆盖率执行");
                    execution = execute(prepared, config, command, command.args,
                            lastProcessOutputAt, logBuffer, logWriterRef, phase);
                }
            } else {
                phase[0] = "coverage";
                output(prepared, "system", "覆盖率执行：" + command.executable + " " + String.join(" ", command.args) + "\n");
                execution = execute(prepared, config, command, command.args,
                        lastProcessOutputAt, logBuffer, logWriterRef, phase);
            }
            output(prepared, "system", "Maven 已结束，退出码：" + execution.exitCode + "\n");
            updateProgress(prepared, 96, MavenProgressStage.summarizing, "正在读取类级与配置目录覆盖率");

            TestResults.CollectOptions collectOptions = new TestResults.CollectOptions();
            collectOptions.config = config;
            collectOptions.prepared = prepared;
            collectOptions.startedAt = startedAt;
            collectOptions.exitCode = execution.exitCode;
            collectOptions.signal = execution.signal;
            collectOptions.logText = logBuffer.toString();
            collectOptions.continueOnTestFailure = options.continueOnTestFailure;
            TestExecutionResult tests = TestResults.collectTestExecutionResult(collectOptions);

            String testSummaryPath = new File(prepared.runDirectory,
                    "round-" + Names.roundLabel(prepared.round) + "-test-summary.txt").getPath();
            int passedTests = Math.max(0, tests.tests - tests.failures - tests.errors - tests.skipped);
            Fs.writeString(testSummaryPath, String.join("\n",
                    "Maven Test Summary - Round " + prepared.round,
                    "Status    : " + tests.status.json(),
                    "Tests run : " + tests.tests,
                    "Passed    : " + passedTests,
                    "Failures  : " + tests.failures,
                    "Errors    : " + tests.errors,
                    "Skipped   : " + tests.skipped,
                    "Maven exit: " + (execution.exitCode == null ? "null" : execution.exitCode),
                    "Full log  : " + prepared.logPath,
                    "Surefire  : " + (tests.reportArchivePath == null ? "no fresh report" : tests.reportArchivePath),
                    "") + "\n");

            List<com.coverageloop.model.CoverageModuleResult> coverage =
                    CoverageReader.readCoverageResults(config, startedAt);
            String finishedAt = Instant.now().toString();
            RunHistory.PersistedCoverage persisted = RunHistory.persistCoverageRound(
                    config, prepared, coverage, startedAt, finishedAt, execution.exitCode, tests);

            output(prepared, "system", "测试状态：" + tests.message + "\n");
            output(prepared, "system", "测试摘要：" + testSummaryPath + "\n");
            if (tests.reportArchivePath != null) {
                output(prepared, "system", "Surefire 报告归档：" + tests.reportArchivePath + "\n");
            }
            output(prepared, "system", "覆盖率快照：" + persisted.coverageSnapshotPath + "\n");
            output(prepared, "system", "分组：初始已满足 " + persisted.groups.initialSatisfied.size()
                    + "，待补充 " + persisted.groups.pending.size()
                    + "，已补充 " + persisted.groups.supplemented.size() + "\n");

            boolean completedWithFailedTests = tests.status == com.coverageloop.model.TestExecutionStatus.test_failed;
            String message = execution.exitCode == 0
                    ? (completedWithFailedTests ? "采集完成，但存在失败测试；覆盖率可能不完整" : "本轮完成，日志与覆盖率快照已保存")
                    : "本轮失败，日志与覆盖率证据已保存";
            updateProgress(prepared, 100,
                    execution.exitCode == 0 ? MavenProgressStage.completed : MavenProgressStage.failed, message);

            MavenRunResult result = new MavenRunResult();
            result.exitCode = execution.exitCode;
            result.signal = execution.signal;
            result.command = command;
            result.preInstall.attempted = shouldPreInstall;
            result.preInstall.exitCode = preInstallExitCode;
            result.runId = prepared.runId;
            result.round = prepared.round;
            result.runDirectory = prepared.runDirectory;
            result.logPath = prepared.logPath;
            result.coverageSnapshotPath = persisted.coverageSnapshotPath;
            result.startedAt = startedAt;
            result.finishedAt = finishedAt;
            result.coverage = coverage;
            result.groups = persisted.groups;
            result.tests = tests;
            return result;
        } catch (Exception error) {
            child.set(null);
            output(prepared, "system", "Maven 执行失败：" + error.getMessage() + "\n");
            updateProgress(prepared, 100, MavenProgressStage.failed, "Maven 进程启动或覆盖率汇总失败，日志已保存");
            throw error;
        }
    }

    private static class ProcessExecution {
        Integer exitCode;
        String signal;
    }

    private ProcessExecution execute(RunHistory.PreparedRound prepared, ProjectConfig config, MavenCommandPreview command,
                                     List<String> args, AtomicLong lastProcessOutputAt, StringBuilder logBuffer,
                                     AtomicReference<LogWriter> logWriterRef, String[] phase) throws Exception {
        Map<String, String> env = new HashMap<>();
        if (command.javaHome != null && !command.javaHome.isBlank()) env.put("JAVA_HOME", command.javaHome);
        List<String> cmdLine = Proc.commandLine(command.executable, args);
        ProcessBuilder builder = new ProcessBuilder(cmdLine);
        builder.directory(new File(command.workingDirectory));
        builder.environment().putAll(env);
        builder.redirectErrorStream(false);
        if (cancelled.getAsBoolean() || stopRequested) throw new java.util.concurrent.CancellationException("任务已停止");
        if ("coverage".equals(phase[0])) {
            for (String module : config.selectedModulePaths) {
                java.nio.file.Path target = java.nio.file.Path.of(command.workingDirectory, module, "target");
                java.nio.file.Files.deleteIfExists(target.resolve("jacoco.exec"));
                java.nio.file.Files.deleteIfExists(target.resolve("site/jacoco/jacoco.xml"));
            }
        }
        Process process = builder.start();
        child.set(process);
        if (cancelled.getAsBoolean() || stopRequested) Proc.killTree(process.pid());
        long pid = process.pid();
        long processStartedAt = System.currentTimeMillis();
        lastProcessOutputAt.set(processStartedAt);
        output(prepared, "system", "[PROCESS_STARTED] PID=" + pid + " time=" + Instant.now() + "\n");

        LogWriter writer = new LogWriter(prepared.logPath);
        logWriterRef.set(writer);
        Thread stdoutThread = pump(process.getInputStream(), chunk -> {
            String text = new String(chunk, StandardCharsets.UTF_8);
            forward(prepared, command, "stdout", text, lastProcessOutputAt, logBuffer, writer, phase);
        });
        Thread stderrThread = pump(process.getErrorStream(), chunk -> {
            String text = new String(chunk, StandardCharsets.UTF_8);
            forward(prepared, command, "stderr", text, lastProcessOutputAt, logBuffer, writer, phase);
        });

        int heartbeatMs = Math.max(1, config.agent.heartbeatSeconds) * 1000;
        AtomicReference<Thread> heartbeatRef = new AtomicReference<>();
        Thread heartbeat = new Thread(() -> {
            try {
                while (true) {
                    Thread.sleep(heartbeatMs);
                    if (process.isAlive()) {
                        long now = System.currentTimeMillis();
                        long elapsed = Math.max(0, (now - processStartedAt) / 1000);
                        long silent = Math.max(0, (now - lastProcessOutputAt.get()) / 1000);
                        output(prepared, "system", "[ALIVE] PID=" + pid + " elapsed=" + elapsed
                                + "s no_output=" + silent + "s time=" + Instant.now() + "\n");
                    }
                }
            } catch (InterruptedException error) {
                // 进程结束时停止心跳
            }
        });
        heartbeat.setDaemon(true);
        heartbeat.start();
        heartbeatRef.set(heartbeat);

        int exitCode;
        try { exitCode = process.waitFor(); }
        catch (InterruptedException e) { Proc.killTree(process.pid()); heartbeat.interrupt(); child.set(null); writer.close(); Thread.currentThread().interrupt(); throw e; }
        heartbeat.interrupt();
        stdoutThread.join();
        stderrThread.join();
        child.set(null);
        output(prepared, "system", "[PROCESS_EXITED] PID=" + pid + " exit_code=" + exitCode
                + " elapsed=" + Math.max(0, (System.currentTimeMillis() - processStartedAt) / 1000) + "s\n");
        writer.close();
        ProcessExecution execution = new ProcessExecution();
        execution.exitCode = exitCode;
        execution.signal = (cancelled.getAsBoolean() || stopRequested) ? "SIGTERM" : null;
        return execution;
    }

    private void forward(RunHistory.PreparedRound prepared, MavenCommandPreview command, String stream,
                         String text, AtomicLong lastProcessOutputAt, StringBuilder logBuffer,
                         LogWriter writer, String[] phase) {
        lastProcessOutputAt.set(System.currentTimeMillis());
        synchronized (logBuffer) {
            String tail = logBuffer.append(text).toString();
            logBuffer.setLength(0);
            logBuffer.append(tail.length() > 2_000_000 ? tail.substring(tail.length() - 2_000_000) : tail);
        }
        output(prepared, stream, text);
        writer.append("[%s] [%s] %s".formatted(Instant.now(), stream, text));
        inferProgress(prepared, command, phase, text.toLowerCase());
    }

    private Thread pump(java.io.InputStream stream, java.util.function.Consumer<byte[]> consumer) {
        Thread thread = new Thread(() -> {
            byte[] buffer = new byte[8192];
            try {
                int read;
                while ((read = stream.read(buffer)) != -1) {
                    if (read > 0) consumer.accept(java.util.Arrays.copyOf(buffer, read));
                }
            } catch (IOException error) {
                // 进程退出后流关闭，正常结束
            }
        });
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private void inferProgress(RunHistory.PreparedRound prepared, MavenCommandPreview command,
                               String[] phase, String value) {
        if ("pre-install".equals(phase[0])) {
            if (value.contains("scanning for projects")) updateProgress(prepared, 8, MavenProgressStage.installing, "正在解析预构建 Reactor");
            if (value.contains("maven-compiler-plugin") || value.contains("compiling ")) updateProgress(prepared, 20, MavenProgressStage.installing, "正在跳过测试并安装项目依赖");
            if (value.contains("build success")) updateProgress(prepared, 32, MavenProgressStage.installing, "预构建安装完成");
            if (value.contains("build failure")) updateProgress(prepared, 32, MavenProgressStage.installing, "预构建失败，正在保存日志");
            return;
        }
        if (value.contains("scanning for projects")) updateProgress(prepared, 10, MavenProgressStage.resolving, "正在解析 Maven Reactor");
        if (value.contains("maven-resources-plugin")) updateProgress(prepared, 40, MavenProgressStage.compiling, "正在准备项目资源");
        if (value.contains("maven-compiler-plugin") || value.contains("compiling ")) updateProgress(prepared, 50, MavenProgressStage.compiling, "正在编译源码与测试");
        if (value.contains("maven-surefire-plugin") || value.contains(" t e s t s")) updateProgress(prepared, 64, MavenProgressStage.testing, "正在执行单元测试");
        if (value.contains("tests run:")) updateProgress(prepared, 76, MavenProgressStage.testing, "测试执行接近完成");
        if (value.contains("jacoco-maven-plugin") && value.contains(":report")) updateProgress(prepared, 84, MavenProgressStage.reporting, "正在生成 JaCoCo 报告");
        if (value.contains("build success")) updateProgress(prepared, 93, MavenProgressStage.summarizing, "Maven 已成功，正在汇总覆盖率");
        if (value.contains("build failure")) updateProgress(prepared, 93, MavenProgressStage.summarizing, "Maven 已失败，正在保存本轮证据");
    }

    private final AtomicInteger lastPercent = new AtomicInteger(0);

    private void updateProgress(RunHistory.PreparedRound prepared, int percent,
                                MavenProgressStage stage, String message) {
        int previous = lastPercent.get();
        if (percent < previous && stage != MavenProgressStage.failed) return;
        lastPercent.set(percent);
        MavenProgressEvent event = new MavenProgressEvent();
        event.runId = prepared.runId;
        event.round = prepared.round;
        event.stage = stage;
        event.percent = percent;
        event.message = message;
        event.timestamp = Instant.now().toString();
        sink.progress(event);
    }

    private void output(RunHistory.PreparedRound prepared, String stream, String text) {
        MavenOutputEvent event = new MavenOutputEvent();
        event.stream = stream;
        event.text = text;
        event.timestamp = Instant.now().toString();
        event.runId = prepared.runId;
        event.round = prepared.round;
        sink.output(event);
    }

    /** 停止当前 Maven 进程树 */
    public boolean stop() {
        stopRequested = true;
        Process process = child.get();
        if (process == null || !process.isAlive()) return false;
        Proc.killTree(process.pid());
        return true;
    }

    private static class LogWriter {
        private final java.io.BufferedWriter writer;

        LogWriter(String path) {
            try {
                Fs.mkdirs(new File(path).getParent());
                writer = new java.io.BufferedWriter(new java.io.OutputStreamWriter(
                        new java.io.FileOutputStream(path, true), StandardCharsets.UTF_8));
            } catch (IOException error) {
                throw new RuntimeException("创建日志文件失败：" + path, error);
            }
        }

        synchronized void append(String text) {
            try {
                writer.write(text);
            } catch (IOException error) {
                // 日志写入失败不影响主流程
            }
        }

        synchronized void close() {
            try {
                writer.flush();
                writer.close();
            } catch (IOException error) {
                // 忽略
            }
        }
    }
}
