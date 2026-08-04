package com.coverageloop.ui;

import com.coverageloop.model.AgentProbeResult;
import com.coverageloop.model.ConfigSummary;
import com.coverageloop.model.CoverageLoopResult;
import com.coverageloop.model.CoverageScope;
import com.coverageloop.model.CoverageStatisticsConfig;
import com.coverageloop.model.CoverageStatisticsGroup;
import com.coverageloop.model.CoverageStatisticsSnapshotInfo;
import com.coverageloop.model.CoverageStatisticsState;
import com.coverageloop.model.CoverageStatisticsSummary;
import com.coverageloop.model.MavenCommandPreview;
import com.coverageloop.model.MavenModule;
import com.coverageloop.model.MavenOutputEvent;
import com.coverageloop.model.MavenProgressEvent;
import com.coverageloop.model.MavenRunResult;
import com.coverageloop.model.ModuleSources;
import com.coverageloop.model.ProjectConfig;
import com.coverageloop.model.ProjectScanResult;
import com.coverageloop.model.ScopeKind;
import com.coverageloop.model.ScopeMode;
import com.coverageloop.service.AgentRunner;
import com.coverageloop.service.ConfigStore;
import com.coverageloop.service.CoverageLoopRunner;
import com.coverageloop.service.CoverageStatisticsService;
import com.coverageloop.service.MavenRunner;
import com.coverageloop.service.ProjectScanner;
import com.coverageloop.util.Names;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.awt.Desktop;
import java.io.File;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 应用状态与动作（对应 Electron 版 App.tsx 的状态与回调） */
public class AppContext {

    public enum View {
        PROJECT("项目初始化", "选择根 POM", "01"),
        MODULES("模块选择", "确定测试范围", "02"),
        SCOPE("包与类", "Include / Exclude", "03"),
        RUN("执行与结果", "Maven + JaCoCo", "04"),
        STATISTICS("覆盖率统计", "分组统计与明细", "Σ");

        final String title;
        final String subtitle;
        final String number;

        View(String title, String subtitle, String number) {
            this.title = title;
            this.subtitle = subtitle;
            this.number = number;
        }
    }

    public final Stage stage;

    // 服务
    public final MavenRunner mavenRunner;
    public final AgentRunner agentRunner;
    public final CoverageLoopRunner loopRunner;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "coverage-loop-worker");
        thread.setDaemon(true);
        return thread;
    });

    // 状态
    public final StringProperty rootPomPath = new SimpleStringProperty("");
    public final ObjectProperty<ProjectScanResult> project = new SimpleObjectProperty<>();
    public final ObjectProperty<ProjectConfig> config = new SimpleObjectProperty<>();
    public final ObservableList<ConfigSummary> configs = FXCollections.observableArrayList();
    public final StringProperty activeModulePath = new SimpleStringProperty("");
    public final Map<String, ModuleSources> sourceCache = new HashMap<>();
    public final ObjectProperty<ModuleSources> activeSources = new SimpleObjectProperty<>();
    public final ObjectProperty<CoverageStatisticsConfig> statisticsConfig = new SimpleObjectProperty<>();
    public final ObjectProperty<CoverageStatisticsSnapshotInfo> statisticsSnapshot = new SimpleObjectProperty<>();
    public final ObjectProperty<CoverageStatisticsSummary> statisticsSummary = new SimpleObjectProperty<>();
    public final StringProperty logs = new SimpleStringProperty("");
    public final ObjectProperty<MavenCommandPreview> preview = new SimpleObjectProperty<>();
    public final ObjectProperty<MavenRunResult> runResult = new SimpleObjectProperty<>();
    public final ObjectProperty<AgentProbeResult> agentProbe = new SimpleObjectProperty<>();
    public final ObjectProperty<CoverageLoopResult> loopResult = new SimpleObjectProperty<>();
    public final ObjectProperty<MavenProgressEvent> progress = new SimpleObjectProperty<>();
    public final BooleanProperty running = new SimpleBooleanProperty(false);
    public final BooleanProperty busy = new SimpleBooleanProperty(false);
    public final StringProperty toastMessage = new SimpleStringProperty("");
    public final BooleanProperty toastError = new SimpleBooleanProperty(false);
    public final ObjectProperty<View> view = new SimpleObjectProperty<>(View.PROJECT);

    public AppContext(Stage stage) {
        this.stage = stage;
        MavenRunner.OutputSink sink = new MavenRunner.OutputSink() {
            @Override
            public void output(MavenOutputEvent event) {
                Platform.runLater(() -> {
                    String current = logs.get();
                    String next = current + event.text;
                    if (next.length() > 180_000) next = next.substring(next.length() - 180_000);
                    logs.set(next);
                });
            }

            @Override
            public void progress(MavenProgressEvent event) {
                Platform.runLater(() -> progress.set(event));
            }
        };
        MavenRunner.RunnerPaths paths = new MavenRunner.RunnerPaths();
        paths.resourcesPath = new File(System.getProperty("user.dir"), "resources").getPath();
        paths.developmentRoot = System.getProperty("user.dir");
        mavenRunner = new MavenRunner(paths, sink);
        agentRunner = new AgentRunner(sink);
        loopRunner = new CoverageLoopRunner(mavenRunner, agentRunner);
        activeModulePath.addListener((observable, oldValue, newValue) ->
                activeSources.set(sourceCache.get(newValue)));
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    @FunctionalInterface
    private interface Task {
        void run() throws Exception;
    }

    /** 在后台线程执行动作，完成后自动切回 FX 线程 */
    private void runAsync(Task task) {
        executor.execute(() -> {
            try {
                task.run();
            } catch (Exception error) {
                Platform.runLater(() -> showError(errorMessage(error)));
            }
        });
    }

    private static String errorMessage(Throwable error) {
        return error.getMessage() == null ? error.toString() : error.getMessage();
    }

    public void showError(String message) {
        toastMessage.set(message);
        toastError.set(true);
    }

    public void showNotice(String message) {
        toastMessage.set(message);
        toastError.set(false);
    }

    // ---------- 项目初始化 ----------

    public void chooseRootPom() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择 Maven 根 POM");
        File selected = chooser.showOpenDialog(stage);
        if (selected != null) rootPomPath.set(selected.getPath());
    }

    public void chooseFile(String title, List<FileChooser.ExtensionFilter> filters, String targetProperty) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(title);
        if (filters != null) chooser.getExtensionFilters().addAll(filters);
        File selected = chooser.showOpenDialog(stage);
        if (selected != null) applyPath(targetProperty, selected.getPath());
    }

    public void chooseDirectory(String title, String targetProperty) {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(title);
        File selected = chooser.showDialog(stage);
        if (selected != null) applyPath(targetProperty, selected.getPath());
    }

    private void applyPath(String targetProperty, String value) {
        ProjectConfig current = config.get();
        if (current == null) return;
        switch (targetProperty) {
            case "maven.executable": current.maven.executable = value; break;
            case "maven.settingsPath": current.maven.settingsPath = value; break;
            case "maven.javaHome": current.maven.javaHome = value; break;
            case "maven.localRepository": current.maven.localRepository = value; break;
            default: return;
        }
        config.set(current.copy());
        clearRunResults();
    }

    public void initialize() {
        String path = rootPomPath.get().trim();
        if (path.isEmpty()) {
            showError("请先选择或输入根 pom.xml 路径");
            return;
        }
        busy.set(true);
        showToastReset();
        runAsync(() -> {
            ProjectScanResult scanned = ProjectScanner.scanMavenProject(path);
            ProjectConfig saved = ConfigStore.loadProjectConfig(scanned.rootPomPath, null);
            List<ConfigSummary> availableConfigs = ConfigStore.listProjectConfigs(scanned.rootPomPath);
            CoverageStatisticsState statisticsState = CoverageStatisticsService.loadCoverageStatisticsState(scanned.rootPomPath);
            ProjectConfig nextConfig = saved != null ? saved : com.coverageloop.model.ConfigFactory.createDefaultConfig(scanned.rootPomPath);
            Set<String> validPaths = new LinkedHashSet<>();
            for (MavenModule module : scanned.modules) validPaths.add(module.relativePath);
            nextConfig.selectedModulePaths.removeIf(pathItem -> !validPaths.contains(pathItem));
            nextConfig.scopes.removeIf(scope -> !validPaths.contains(scope.modulePath));
            CoverageStatisticsConfig nextStatistics = statisticsState.config;
            for (CoverageStatisticsGroup group : nextStatistics.groups) {
                group.modulePaths.removeIf(modulePath -> !validPaths.contains(modulePath));
                group.scopes.removeIf(scope -> !validPaths.contains(scope.modulePath));
            }
            ProjectConfig copy = nextConfig.copy();
            String firstModule = !copy.selectedModulePaths.isEmpty()
                    ? copy.selectedModulePaths.get(0)
                    : scanned.modules.stream().filter(module -> module.hasMainSources)
                            .map(module -> module.relativePath).findFirst().orElse("");
            Platform.runLater(() -> {
                rootPomPath.set(scanned.rootPomPath);
                project.set(scanned);
                config.set(copy);
                configs.setAll(availableConfigs);
                statisticsConfig.set(nextStatistics);
                statisticsSnapshot.set(statisticsState.snapshot);
                statisticsSummary.set(statisticsState.summary);
                sourceCache.clear();
                activeModulePath.set(firstModule);
                showNotice(saved != null ? "已载入已有项目配置"
                        : "初始化完成，发现 " + scanned.modules.size() + " 个模块");
                view.set(View.MODULES);
                busy.set(false);
            });
        });
    }

    private void showToastReset() {
        toastMessage.set("");
        toastError.set(false);
    }

    // ---------- 配置管理 ----------

    public void switchConfig(String configId) {
        ProjectScanResult currentProject = project.get();
        if (currentProject == null || running.get()) return;
        runAsync(() -> {
            ProjectConfig loaded = ConfigStore.loadProjectConfig(currentProject.rootPomPath, configId);
            if (loaded == null) return;
            Set<String> validPaths = new LinkedHashSet<>();
            for (MavenModule module : currentProject.modules) validPaths.add(module.relativePath);
            loaded.selectedModulePaths.removeIf(item -> !validPaths.contains(item));
            loaded.scopes.removeIf(scope -> !validPaths.contains(scope.modulePath));
            ProjectConfig copy = loaded.copy();
            Platform.runLater(() -> {
                config.set(copy);
                activeModulePath.set(copy.selectedModulePaths.isEmpty() ? "" : copy.selectedModulePaths.get(0));
                clearRunResults();
                showNotice("已切换到配置：" + copy.name);
            });
        });
    }

    public void createConfigCopy() {
        ProjectScanResult currentProject = project.get();
        ProjectConfig current = config.get();
        if (currentProject == null || current == null || running.get()) return;
        runAsync(() -> {
            if (configs.stream().noneMatch(item -> item.id.equals(current.id))) {
                ConfigStore.saveProjectConfig(current);
            }
            ProjectConfig copy = current.copy();
            copy.id = "config-" + System.currentTimeMillis();
            copy.name = current.name + " 副本";
            copy.createdAt = Names.nowIso();
            copy.updatedAt = Names.nowIso();
            ConfigStore.saveProjectConfig(copy);
            List<ConfigSummary> updated = ConfigStore.listProjectConfigs(currentProject.rootPomPath);
            Platform.runLater(() -> {
                config.set(copy);
                configs.setAll(updated);
                clearRunResults();
                showNotice("已创建新的独立配置");
            });
        });
    }

    public void deleteCurrentConfig() {
        ProjectScanResult currentProject = project.get();
        ProjectConfig current = config.get();
        if (currentProject == null || current == null || configs.size() <= 1 || running.get()) return;
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                "确定删除配置“" + current.name + "”吗？执行日志不会删除。", ButtonType.OK, ButtonType.CANCEL);
        alert.setHeaderText(null);
        alert.showAndWait().ifPresent(result -> {
            if (result != ButtonType.OK) return;
            runAsync(() -> {
                ConfigStore.deleteProjectConfig(currentProject.rootPomPath, current.id);
                List<ConfigSummary> remaining = ConfigStore.listProjectConfigs(currentProject.rootPomPath);
                ProjectConfig next = remaining.isEmpty()
                        ? com.coverageloop.model.ConfigFactory.createDefaultConfig(currentProject.rootPomPath)
                        : ConfigStore.loadProjectConfig(currentProject.rootPomPath, remaining.get(0).id);
                ProjectConfig copy = next.copy();
                Platform.runLater(() -> {
                    configs.setAll(remaining);
                    config.set(copy);
                    clearRunResults();
                    showNotice("配置已删除，历史执行日志仍然保留");
                });
            });
        });
    }

    public void save() {
        ProjectConfig current = config.get();
        if (current == null) return;
        busy.set(true);
        runAsync(() -> {
            String path = ConfigStore.saveProjectConfig(current);
            List<ConfigSummary> updated = ConfigStore.listProjectConfigs(current.rootPomPath);
            Platform.runLater(() -> {
                configs.setAll(updated);
                busy.set(false);
                showNotice("配置已保存：" + path);
            });
        });
    }

    public void refreshConfigs() {
        ProjectScanResult currentProject = project.get();
        if (currentProject == null) return;
        runAsync(() -> {
            List<ConfigSummary> updated = ConfigStore.listProjectConfigs(currentProject.rootPomPath);
            Platform.runLater(() -> configs.setAll(updated));
        });
    }

    public void clearRunResults() {
        preview.set(null);
        runResult.set(null);
        agentProbe.set(null);
        loopResult.set(null);
        progress.set(null);
    }

    public void clearConsole() {
        logs.set("");
    }

    public void startNewBaseline() {
        runResult.set(null);
        loopResult.set(null);
        progress.set(null);
        clearConsole();
        showNotice("下一次执行将建立新的覆盖率基线");
    }

    // ---------- 模块与范围 ----------

    public void toggleModule(String modulePath) {
        ProjectConfig current = config.get();
        if (current == null) return;
        ProjectConfig copy = current.copy();
        if (copy.selectedModulePaths.contains(modulePath)) {
            copy.selectedModulePaths.remove(modulePath);
            copy.scopes.removeIf(scope -> scope.modulePath.equals(modulePath));
        } else {
            copy.selectedModulePaths.add(modulePath);
        }
        config.set(copy);
        clearRunResults();
        activeModulePath.set(modulePath);
    }

    public void setScope(ScopeMode mode, ScopeKind kind, String pattern) {
        String modulePath = activeModulePath.get();
        ProjectConfig current = config.get();
        if (modulePath == null || modulePath.isEmpty() || current == null) return;
        ProjectConfig copy = current.copy();
        ScopeMode existingMode = copy.scopes.stream()
                .filter(scope -> scope.modulePath.equals(modulePath) && scope.kind == kind && scope.pattern.equals(pattern))
                .map(scope -> scope.mode).findFirst().orElse(null);
        copy.scopes.removeIf(scope -> scope.modulePath.equals(modulePath)
                && scope.kind == kind && scope.pattern.equals(pattern));
        if (existingMode != mode) {
            copy.scopes.add(new CoverageScope(modulePath, mode, kind, pattern));
        }
        config.set(copy);
        clearRunResults();
    }

    public CoverageScope scopeFor(ScopeKind kind, String pattern) {
        String modulePath = activeModulePath.get();
        ProjectConfig current = config.get();
        if (modulePath == null || current == null) return null;
        return current.scopes.stream()
                .filter(scope -> scope.modulePath.equals(modulePath) && scope.kind == kind && scope.pattern.equals(pattern))
                .findFirst().orElse(null);
    }

    public void loadModuleSources(MavenModule module) {
        if (sourceCache.containsKey(module.relativePath)) return;
        runAsync(() -> {
            ModuleSources sources = ProjectScanner.scanModuleSources(module);
            Platform.runLater(() -> {
                sourceCache.put(module.relativePath, sources);
                activeSources.set(sources);
            });
        });
    }

    // ---------- 执行 ----------

    public void previewCommand() {
        ProjectConfig current = config.get();
        if (current == null) return;
        runAsync(() -> {
            MavenCommandPreview command = mavenRunner.resolveMavenCommand(current, new MavenRunner.RunOptions());
            Platform.runLater(() -> preview.set(command));
        });
    }

    public void runMaven() {
        ProjectConfig current = config.get();
        if (current == null) return;
        MavenRunResult previous = runResult.get();
        if (previous != null && current.agent.enabled && previous.round >= current.agent.maxRounds) {
            showError("当前执行已达到配置的最大轮数 " + current.agent.maxRounds + "，请新建一组基线或调大循环次数");
            return;
        }
        running.set(true);
        clearConsole();
        runResult.set(null);
        showToastReset();
        runAsync(() -> {
            ConfigStore.saveProjectConfig(current);
            MavenRunner.RunOptions options = new MavenRunner.RunOptions();
            MavenRunResult result = mavenRunner.run(current, previous == null ? null : previous.runId, options);
            Platform.runLater(() -> {
                runResult.set(result);
                preview.set(result.command);
                showNotice(result.exitCode != null && result.exitCode == 0
                        ? (result.tests.status == com.coverageloop.model.TestExecutionStatus.no_tests
                                ? "第 " + result.round + " 轮没有可执行测试，已按 0% 建立覆盖率基线"
                                : "第 " + result.round + " 轮完成，日志与覆盖率快照已保存")
                        : "第 " + result.round + " 轮 Maven 失败，日志已保存，退出码 " + result.exitCode);
                running.set(false);
            });
        });
    }

    public void probeConfiguredAgent() {
        ProjectConfig current = config.get();
        if (current == null || !current.agent.enabled) {
            showError("请先选择 Hermes 或 OpenCode");
            return;
        }
        running.set(true);
        clearConsole();
        showToastReset();
        runAsync(() -> {
            ConfigStore.saveProjectConfig(current);
            AgentProbeResult result = agentRunner.probe(current);
            Platform.runLater(() -> {
                agentProbe.set(result);
                showNotice(result.status == com.coverageloop.model.AgentExecutionStatus.passed
                        ? "Agent 验证成功：" + result.executable + " 已回复 OK"
                        : "Agent 验证失败：" + result.executable + " 未正常回复 OK，未启动补测");
                running.set(false);
            });
        });
    }

    public void runAutomaticLoop() {
        ProjectConfig current = config.get();
        if (current == null || !current.agent.enabled) {
            showError("请先选择 Hermes 或 OpenCode");
            return;
        }
        running.set(true);
        clearConsole();
        runResult.set(null);
        loopResult.set(null);
        showToastReset();
        runAsync(() -> {
            ConfigStore.saveProjectConfig(current);
            CoverageLoopResult result = loopRunner.run(current);
            Platform.runLater(() -> {
                agentProbe.set(result.probe);
                loopResult.set(result);
                if (result.latest != null) {
                    runResult.set(result.latest);
                    preview.set(result.latest.command);
                }
                showNotice(result.message);
                running.set(false);
            });
        });
    }

    public void stopMaven() {
        if (loopRunner.stop()) showNotice("已发送停止信号");
    }

    // ---------- 覆盖率统计 ----------

    public void saveStatistics() {
        CoverageStatisticsConfig current = statisticsConfig.get();
        if (current == null) return;
        busy.set(true);
        runAsync(() -> {
            String path = CoverageStatisticsService.saveCoverageStatisticsConfig(current);
            Platform.runLater(() -> {
                busy.set(false);
                showNotice("覆盖率统计草稿已保存：" + path);
            });
        });
    }

    public void confirmStatistics(CoverageStatisticsConfig value) {
        busy.set(true);
        showToastReset();
        runAsync(() -> {
            CoverageStatisticsState state = CoverageStatisticsService.recalculateCoverageStatistics(value);
            Platform.runLater(() -> {
                statisticsConfig.set(state.config);
                statisticsSnapshot.set(state.snapshot);
                statisticsSummary.set(state.summary);
                busy.set(false);
                showNotice(state.snapshot != null
                        ? "分组已确认，已基于最近一次覆盖率快照重新统计全部已确认分组"
                        : "分组已确认；运行一次选定模块 Test 后会生成统计结果");
            });
        });
    }

    public void runCoverageStatistics(CoverageStatisticsConfig statistics) {
        ProjectConfig current = config.get();
        ProjectScanResult currentProject = project.get();
        if (current == null || currentProject == null || statistics == null) return;
        running.set(true);
        clearConsole();
        progress.set(null);
        showToastReset();
        runAsync(() -> {
            ConfigStore.saveProjectConfig(current);
            CoverageStatisticsService.saveCoverageStatisticsConfig(statistics);
            List<String> modulePaths = CoverageStatisticsService
                    .coverageStatisticsExecutionModulePaths(currentProject, statistics);
            if (modulePaths.isEmpty()) {
                throw new IllegalArgumentException("请先在统计分组中选择至少一个模块");
            }
            ProjectConfig executionConfig = current.copy();
            executionConfig.id = "coverage-statistics";
            executionConfig.name = "覆盖率统计";
            executionConfig.selectedModulePaths = modulePaths;
            executionConfig.scopes.clear();
            executionConfig.agent.enabled = false;
            MavenRunner.RunOptions options = new MavenRunner.RunOptions();
            options.continueOnTestFailure = true;
            MavenRunResult result = mavenRunner.run(executionConfig, null, options);
            CoverageStatisticsService.PersistedStatistics persisted =
                    CoverageStatisticsService.persistCoverageStatisticsSnapshot(statistics, result);
            Platform.runLater(() -> {
                statisticsSnapshot.set(persisted.snapshot);
                statisticsSummary.set(persisted.summary);
                if (persisted.snapshot.tests.status == com.coverageloop.model.TestExecutionStatus.test_failed
                        && persisted.snapshot.tests.continuedAfterFailure) {
                    showNotice("选定模块采集已完成，但有 " + persisted.snapshot.tests.failures + " 个失败、"
                            + persisted.snapshot.tests.errors + " 个错误；统计结果可能不完整");
                } else if (result.exitCode != null && result.exitCode == 0) {
                    showNotice("选定模块测试完成，覆盖率快照已保存；后续确认分组不会重复运行测试");
                } else {
                    showNotice("选定模块测试结束，退出码 " + result.exitCode + "；日志与当前覆盖率快照已保存");
                }
                running.set(false);
            });
        });
    }

    public void openPath(String path) {
        if (path == null || path.isEmpty()) return;
        runAsync(() -> {
            try {
                File target = new File(path);
                if (Desktop.isDesktopSupported()) {
                    Desktop.getDesktop().open(target);
                    return;
                }
            } catch (Exception error) {
                // 尝试命令行打开
            }
            try {
                if (com.coverageloop.util.Proc.isWindows()) {
                    new ProcessBuilder("explorer", "/select," + path).start();
                }
            } catch (Exception ignored) {
                // 无法打开时静默
            }
        });
    }

    /** 供 UI 使用的异步任务包装 */
    public <T> CompletableFuture<T> async(java.util.function.Supplier<T> supplier) {
        CompletableFuture<T> future = new CompletableFuture<>();
        executor.execute(() -> {
            try {
                future.complete(supplier.get());
            } catch (Exception error) {
                future.completeExceptionally(error);
            }
        });
        return future;
    }
}
