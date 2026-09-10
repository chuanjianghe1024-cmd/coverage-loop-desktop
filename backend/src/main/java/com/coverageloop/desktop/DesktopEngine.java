package com.coverageloop.desktop;

import com.coverageloop.model.*;
import com.coverageloop.service.*;
import com.coverageloop.util.*;
import com.google.gson.JsonObject;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns one active task. UI events are bounded; complete execution evidence remains on disk. */
public final class DesktopEngine implements AutoCloseable {
    private final WorkspaceStore store;
    private final List<MavenRunResult> rounds = new ArrayList<>();
    private final List<AgentRoundResult> agentRounds = new ArrayList<>();
    public DesktopEngine() { this(WorkspaceStore.defaultPath()); }
    public DesktopEngine(Path file) { store = new WorkspaceStore(file); }
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final ArrayDeque<Event> events = new ArrayDeque<>();
    private long sequence;
    private String status = "idle", jobId = "", mode = "", message = "选择一个 Maven 工程开始";
    private String startedAt, finishedAt;
    private MavenProgressEvent progress;
    private MavenRunResult latest;
    private CoverageLoopResult loopResult;
    private AgentProbeResult probe;
    private StatisticsTree.Node statistics;
    private volatile MavenRunner maven;
    private volatile AgentRunner agent;
    private volatile CoverageLoopRunner loop;
    private ProjectConfig activeConfig;
    public record Event(long id, String type, Object data) {}

    public Object request(String route, JsonObject input) throws Exception {
        return switch (route) {
            case "/projects" -> store.projects();
            case "/project/open" -> open(required(input, "rootPomPath"));
            case "/config/save" -> save(config(input));
            case "/config/load" -> store.config(required(input,"rootPomPath"), required(input,"id"));
            case "/statistics/configs" -> store.statisticsConfigs(required(input,"rootPomPath"));
            case "/statistics/save" -> saveStatistics(config(input));
            case "/statistics/delete" -> deleteStatistics(required(input,"rootPomPath"),required(input,"id"));
            case "/statistics/preview" -> ScopedTests.plan(config(input));
            case "/command/preview" -> runner().resolveMavenCommand(config(input), new MavenRunner.RunOptions());
            case "/run/start" -> start(config(input), required(input,"mode"));
            case "/run/stop" -> stop();
            case "/state" -> snapshot(input.has("cursor") ? input.get("cursor").getAsLong() : 0);
            case "/round/records" -> new RoundRecords(store).list(input);
            case "/round/file" -> new RoundRecords(store).read(input);
            case "/round/recovery" -> recover(input);
            case "/history" -> store.history(required(input,"rootPomPath"));
            case "/history/detail" -> store.detail(required(input,"rootPomPath"), required(input,"id"));
            default -> throw new IllegalArgumentException("Unknown operation");
        };
    }
    private synchronized Object recover(JsonObject input) throws Exception { requireIdle(); return new RoundRecords(store).recovery(input); }
    private static String required(JsonObject obj, String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull() || obj.get(key).getAsString().isBlank()) throw new IllegalArgumentException("缺少参数：" + key);
        return obj.get(key).getAsString();
    }
    private static ProjectConfig config(JsonObject input) {
        JsonObject object = input.getAsJsonObject("config");
        if (object == null) throw new IllegalArgumentException("缺少任务配置");
        ProjectConfig c = ConfigStore.parseConfig(object.toString(), required(object, "rootPomPath"));
        c.maven.extraArgs = c.maven.extraArgs.stream().filter(a -> a != null && !a.isBlank()).toList();
        c.agent.extraArgs = c.agent.extraArgs.stream().filter(a -> a != null && !a.isBlank()).toList();
        return c;
    }
    private synchronized void requireIdle() {
        if (status.equals("running") || status.equals("stopping")) throw new IllegalStateException("请等待当前任务结束或先停止任务");
    }
    private synchronized Object open(String root) throws Exception {
        if (Files.isDirectory(Path.of(root))) root = Path.of(root,"pom.xml").toString();
        requireIdle();
        ProjectScanResult project = ProjectScanner.scanMavenProject(root);
        List<ModuleSources> sources = project.modules.stream().map(ProjectScanner::scanModuleSources).toList();
        store.touchProject(project.rootPomPath, project.rootArtifactId);
        List<ProjectConfig> saved = store.configs(project.rootPomPath);
        if (saved.isEmpty()) {
            for (ConfigSummary old : ConfigStore.listProjectConfigs(project.rootPomPath)) store.saveConfig(ConfigStore.loadProjectConfig(project.rootPomPath, old.id));
            saved = store.configs(project.rootPomPath);
        }
        ProjectConfig config = saved.isEmpty() ? ConfigFactory.createDefaultConfig(project.rootPomPath) : saved.get(0);
        if (config.selectedModulePaths.isEmpty()) config.selectedModulePaths = project.modules.stream().filter(m -> m.hasMainSources).map(m -> m.relativePath).toList();
        status = "idle"; jobId = ""; mode = ""; message = "工作区已准备就绪";
        latest = null; loopResult = null; probe = null; statistics=null; progress = null; startedAt = null; finishedAt = null;
        rounds.clear(); agentRounds.clear(); events.clear(); activeConfig = config;
        return Map.of("project", project, "sources", sources, "config", config, "configs", saved);
    }
    private synchronized Object save(ProjectConfig config) throws Exception {
        requireIdle(); validate(config, false);
        store.saveConfig(config);
        return Map.of("configs", store.configs(config.rootPomPath));
    }
    private synchronized Object saveStatistics(ProjectConfig config) throws Exception {
        requireIdle(); validate(config,false); config.agent.enabled=false; config.maven.testPattern="";
        String path=store.saveStatisticsConfig(config);
        return Map.of("configs",store.statisticsConfigs(config.rootPomPath),"path",path);
    }
    private synchronized Object deleteStatistics(String root,String id) throws Exception {
        requireIdle(); store.deleteStatisticsConfig(root,id);
        return store.statisticsConfigs(root);
    }
    static void validate(ProjectConfig c, boolean execution) {
        ProjectScanResult project = ProjectScanner.scanMavenProject(c.rootPomPath);
        Set<String> modules = new HashSet<>(project.modules.stream().map(m -> m.relativePath).toList());
        if (execution && c.selectedModulePaths.isEmpty()) throw new IllegalArgumentException("请至少选择一个模块");
        if (!modules.containsAll(c.selectedModulePaths)) throw new IllegalArgumentException("配置包含不属于当前工程的模块，请重新扫描");
        if (c.coverage.lineThreshold <= 0 || c.coverage.lineThreshold > 100) throw new IllegalArgumentException("行覆盖率目标应大于 0 且不超过 100");
        if (c.coverage.branchThreshold != null && c.coverage.branchThreshold != 0) throw new IllegalArgumentException("当前重建版仅支持行覆盖率门禁，请将分支阈值设为 0");
        if (!c.coverage.jacocoVersion.matches("[0-9]+\\.[0-9]+\\.[0-9]+(?:[-.][A-Za-z0-9]+)*")) throw new IllegalArgumentException("JaCoCo 版本格式无效");
        if (c.agent.maxRounds < 1 || c.agent.maxRounds > 1000 || c.agent.batchSize < 1 || c.agent.maxSameFailures < 1 || c.agent.timeoutMinutes < 1) throw new IllegalArgumentException("轮次、批次、熔断次数和超时必须为正数");
        if (!List.of("hermes","opencode").contains(c.agent.provider)) throw new IllegalArgumentException("不支持的 Agent");
        for (CoverageScope scope : c.scopes) if (!modules.contains(scope.modulePath)) throw new IllegalArgumentException("统计范围包含未知模块");
        if (!c.maven.javaHome.isBlank() && !Files.isRegularFile(Path.of(c.maven.javaHome,"bin", Proc.isWindows() ? "java.exe" : "java"))) throw new IllegalArgumentException("目标工程 JDK 路径无效");
        if (!c.maven.settingsPath.isBlank() && !Files.isRegularFile(Path.of(c.maven.settingsPath))) throw new IllegalArgumentException("settings.xml 不存在");
        if (!c.maven.executable.isBlank() && !Files.isRegularFile(Path.of(c.maven.executable))) throw new IllegalArgumentException("Maven 可执行文件不存在");
    }
    private MavenRunner runner() {
        MavenRunner.RunnerPaths paths = new MavenRunner.RunnerPaths();
        paths.resourcesPath = System.getProperty("user.dir"); paths.developmentRoot = paths.resourcesPath;
        return new MavenRunner(paths, sink(), cancelled::get);
    }
    private MavenRunner.OutputSink sink() {
        return new MavenRunner.OutputSink() {
            public void output(MavenOutputEvent event) { emit("log", event); }
            public void progress(MavenProgressEvent event) { synchronized (DesktopEngine.this) { if((event.stage==MavenProgressStage.agent_running||event.stage==MavenProgressStage.agent_probing)&&progress!=null)event.modules=progress.modules; progress = event; } }
        };
    }
    private synchronized void emit(String type, Object data) {
        events.addLast(new Event(++sequence, type, data));
        while (events.size() > 1500) events.removeFirst();
    }
    private synchronized Object start(ProjectConfig config, String taskMode) throws Exception {
        requireIdle(); validate(config, true);
        if (!List.of("baseline","loop","probe","statistics").contains(taskMode)) throw new IllegalArgumentException("无效任务类型");
        if (!taskMode.equals("probe") && ScopedTests.plan(config).stream().noneMatch(s -> s.sourceCount()>0)) throw new IllegalArgumentException("请在树中选择至少一个生产类");
        if (taskMode.equals("loop") && !config.agent.enabled) throw new IllegalArgumentException("请在 Agent 设置中启用自动补测");
        cancelled.set(false); latest = null; loopResult = null; probe = null; statistics=null; progress = null;
        status = "running"; mode = taskMode; jobId = UUID.randomUUID().toString(); activeConfig = config.copy();
        startedAt = Instant.now().toString(); finishedAt = null; message = "正在启动任务";
        events.clear(); rounds.clear(); agentRounds.clear();
        try { if (taskMode.equals("statistics")) store.saveStatisticsConfig(config); else store.saveConfig(config); persist(); }
        catch (Exception e) { status = "failed"; message = "无法保存任务，未启动执行"; throw e; }
        maven = runner(); agent = new AgentRunner(sink(), cancelled::get);
        loop = new CoverageLoopRunner(maven, agent, this::recordRound, result -> { synchronized (DesktopEngine.this) { agentRounds.add(result); try { persist(); } catch (Exception e) { throw new IllegalStateException("保存 Agent 轮次失败", e); } } });
        worker.submit(() -> execute(config, taskMode));
        return Map.of("id", jobId);
    }
    private void execute(ProjectConfig config, String taskMode) {
        try {
            if (cancelled.get()) throw new CancellationException();
            switch (taskMode) {
                case "baseline", "statistics" -> {
                    MavenRunner.RunOptions options=new MavenRunner.RunOptions(); options.scopeTests=true; options.continueOnTestFailure=true;
                    MavenRunResult result = maven.run(config, null, options);
                    if (taskMode.equals("statistics")) {
                        synchronized(this) { statistics=StatisticsTree.build(config,result.coverage); }
                        Fs.writeString(Path.of(result.runDirectory,"statistics-tree.json").toString(),Json.toJson(statistics)+"\n");
                    }
                    recordRound(result);
                    synchronized (this) {
                        latest = result;
                        boolean valid = CoverageLoopRunner.canCollectCoverage(result);
                        status = Objects.equals(result.exitCode, 0) && result.tests.status != TestExecutionStatus.test_failed && valid ? "completed" : "failed";
                        message = valid ? result.tests.message : "缺少本轮有效 JaCoCo 报告或范围未匹配到类，请检查日志";
                    }
                }
                case "probe" -> {
                    AgentProbeResult result = agent.probe(config);
                    synchronized (this) { probe = result; status = result.status == AgentExecutionStatus.passed ? "completed" : "failed"; message = result.status == AgentExecutionStatus.passed ? "Agent 探活成功" : "Agent 探活失败，请检查日志"; }
                }
                case "loop" -> {
                    CoverageLoopResult result = loop.run(config);
                    synchronized (this) { loopResult = result; latest = result.latest; status = result.stopReason == CoverageLoopStopReason.target_reached ? "completed" : result.stopReason == CoverageLoopStopReason.max_rounds ? "limited" : "failed"; message = result.message; }
                }
            }
        } catch (Exception e) {
            synchronized (this) { status = "failed"; message = e.getMessage() == null ? "任务执行失败" : e.getMessage(); }
        } finally {
            synchronized (this) {
                if (cancelled.get()) { status = "cancelled"; message = "任务已停止；已有日志和代码变更已保留"; }
                finishedAt = Instant.now().toString();
                try { persist(); } catch (Exception e) { message += "；保存任务记录失败：" + e.getMessage(); }
                emit("finished", Map.of("status", status, "message", message));
            }
        }
    }
    private synchronized Object stop() {
        if (status.equals("running") || status.equals("stopping")) {
            cancelled.set(true); status = "stopping"; message = "正在停止 Maven 和 Agent 进程";
            if (loop != null) loop.stop(); if (maven != null) maven.stop(); if (agent != null) agent.stop();
        }
        return Map.of("status", status);
    }
    public synchronized Map<String,Object> snapshot(long cursor) {
        Map<String,Object> value = new LinkedHashMap<>();
        value.put("id",jobId); value.put("status",status); value.put("mode",mode); value.put("message",message);
        value.put("startedAt",startedAt); value.put("finishedAt",finishedAt); value.put("progress",progress);
        value.put("statistics",statistics);
        value.put("configId",activeConfig==null?null:activeConfig.id); value.put("configName",activeConfig==null?null:activeConfig.name);
        value.put("agentRounds",new ArrayList<>(agentRounds)); value.put("rounds",new ArrayList<>(rounds)); value.put("latest",latest); value.put("loop",loopResult); value.put("probe",probe); value.put("cursor",sequence);
        value.put("events", events.stream().filter(e -> e.id() > cursor).toList());
        value.put("truncated", !events.isEmpty() && cursor > 0 && cursor < events.getFirst().id() - 1);
        return value;
    }
    private synchronized void recordRound(MavenRunResult result) {
        latest = result; rounds.add(result);
        try { store.saveRound(jobId,result); persist(); }
        catch (Exception e) { throw new IllegalStateException("保存轮次失败：" + e.getMessage(),e); }
        emit("round",result);
    }
    private void persist() throws Exception { store.saveJob(snapshot(sequence),activeConfig); }
    @Override public void close() { stop(); worker.shutdownNow();
        try { worker.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        store.close(); }
}
