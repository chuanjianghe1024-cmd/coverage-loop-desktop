package com.coverageloop.ui;

import com.coverageloop.model.AgentOptions;
import com.coverageloop.model.AgentProbeResult;
import com.coverageloop.model.AgentRoundResult;
import com.coverageloop.model.CoverageGroupedClass;
import com.coverageloop.model.CoverageLoopResult;
import com.coverageloop.model.CoverageModuleResult;
import com.coverageloop.model.CoverageScopeResult;
import com.coverageloop.model.MavenCommandPreview;
import com.coverageloop.model.MavenProgressEvent;
import com.coverageloop.model.MavenRunResult;
import com.coverageloop.model.ProjectConfig;
import com.coverageloop.model.TestExecutionStatus;
import com.coverageloop.util.Text;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;

/** 视图 04：执行与结果（Maven + JaCoCo + Agent 自动循环） */
public class RunView {

    private final AppContext context;
    private final TextArea console = new TextArea();
    private final Label progressMessage = new Label("尚未开始");
    private final Label progressPercent = new Label("0%");
    private final Region progressFill = new Region();
    private final Region progressTrack = new Region();
    private final VBox resultsBox = new VBox(14);
    private final VBox actionsBox = new VBox(10);
    private final Label statusPill = new Label("READY");
    private final Label consoleCaption = new Label("等待执行");
    private String selectedGroup = "pending";

    public RunView(AppContext context) {
        this.context = context;
    }

    public Node build() {
        HBox layout = new HBox(14);
        layout.setPrefWidth(1280);

        // 左侧
        VBox left = new VBox(14);
        HBox.setHgrow(left, Priority.ALWAYS);
        left.setMaxWidth(880);
        left.getChildren().add(buildMavenPanel());
        left.getChildren().add(buildAgentPanel());
        resultsBox.getStyleClass().add("results-box");
        left.getChildren().add(resultsBox);
        ScrollPane leftScroll = new ScrollPane(left);
        leftScroll.setFitToWidth(true);
        leftScroll.setPrefWidth(880);

        // 右侧：控制台
        VBox consolePanel = new VBox(12);
        consolePanel.getStyleClass().add("panel");
        consolePanel.setPrefWidth(420);
        HBox consoleHeading = Widgets.heading("执行日志", (String) null);
        consoleCaption.getStyleClass().add("panel-subtitle");
        consoleHeading.getChildren().add(1, consoleCaption);
        consoleHeading.getChildren().add(statusPill);
        consolePanel.getChildren().add(consoleHeading);

        VBox progressCard = new VBox(6);
        progressCard.getStyleClass().add("progress-card");
        HBox progressText = new HBox();
        progressText.setAlignment(Pos.CENTER_LEFT);
        progressMessage.getStyleClass().add("form-label");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        progressPercent.getStyleClass().add("form-label");
        progressPercent.setStyle("-fx-font-weight: bold;");
        progressText.getChildren().addAll(progressMessage, spacer, progressPercent);
        progressCard.getChildren().add(progressText);
        progressTrack.getStyleClass().add("progress-track");
        progressTrack.setMaxWidth(Double.MAX_VALUE);
        progressTrack.setPrefHeight(8);
        progressFill.getStyleClass().add("progress-fill");
        progressFill.setMinHeight(8);
        progressFill.setPrefHeight(8);
        progressFill.setMaxHeight(8);
        progressFill.setMinWidth(0);
        javafx.scene.layout.StackPane stack = new javafx.scene.layout.StackPane(progressTrack, progressFill);
        stack.setAlignment(Pos.CENTER_LEFT);
        stack.setMaxWidth(Double.MAX_VALUE);
        progressFill.maxWidthProperty().bind(progressTrack.widthProperty());
        progressCard.getChildren().add(stack);
        consolePanel.getChildren().add(progressCard);

        console.setEditable(false);
        console.getStyleClass().add("console");
        console.setWrapText(false);
        console.textProperty().bind(context.logs);
        console.setPrefHeight(520);
        VBox.setVgrow(console, Priority.ALWAYS);
        consolePanel.getChildren().add(console);

        actionsBox.getChildren().clear();
        consolePanel.getChildren().add(actionsBox);

        layout.getChildren().addAll(leftScroll, consolePanel);

        // 事件
        context.running.addListener((observable, oldValue, newValue) -> refreshConsoleState());
        context.progress.addListener((observable, oldValue, newValue) -> updateProgress(newValue));
        context.runResult.addListener((observable, oldValue, newValue) -> {
            refreshResults();
            refreshConsoleState();
        });
        context.loopResult.addListener((observable, oldValue, newValue) -> {
            refreshResults();
            refreshConsoleState();
        });
        context.agentProbe.addListener((observable, oldValue, newValue) -> refreshResults());
        refreshResults();
        refreshConsoleState();
        return layout;
    }

    private Node buildMavenPanel() {
        VBox panel = new VBox(12);
        panel.getStyleClass().add("panel");
        panel.getChildren().add(Widgets.heading("工具链与 Maven 参数", "安装包内置 Maven，JDK 使用本机环境",
                Widgets.label("ADVANCED", "tag")));

        CheckBox bundled = new CheckBox("优先使用内置 Maven");
        bundled.setSelected(context.config.get().maven.useBundledMaven);
        bundled.selectedProperty().addListener((observable, oldValue, newValue) ->
                context.config.get().maven.useBundledMaven = newValue);
        Label bundledHint = Widgets.label("内置工具链不存在时自动回退", "form-hint");
        panel.getChildren().add(toggleRow(bundled, bundledHint));

        CheckBox preInstall = new CheckBox("首轮先执行 skip tests install");
        preInstall.setSelected(context.config.get().maven.preInstall);
        preInstall.selectedProperty().addListener((observable, oldValue, newValue) ->
                context.config.get().maven.preInstall = newValue);
        Label preInstallHint = Widgets.label("每组新基线仅执行一次，先把多模块依赖安装到本地仓库", "form-hint");
        panel.getChildren().add(toggleRow(preInstall, preInstallHint));

        HBox grid = new HBox(10);
        grid.setAlignment(Pos.CENTER_LEFT);
        TextField mavenExec = textField(context.config.get().maven.executable, "留空自动识别");
        TextField javaHome = textField(context.config.get().maven.javaHome, "默认使用 JAVA_HOME / PATH");
        TextField settings = textField(context.config.get().maven.settingsPath, "公司私服配置");
        TextField localRepo = textField(context.config.get().maven.localRepository, "例如 D:\\m2");
        TextField profiles = textField(String.join(",", context.config.get().maven.profiles), "dev,company-repo");
        TextField extraArgs = textField(String.join(" ", context.config.get().maven.extraArgs), "-Dversion_number=1.0.0");
        TextField parallel = textField(String.valueOf(context.config.get().maven.parallelThreads), "");
        TextField testPattern = textField(context.config.get().maven.testPattern, "例如 *ServiceTest；留空执行所选模块全部测试");

        VBox col1 = new VBox(8);
        col1.getChildren().add(Widgets.field("Maven 可执行文件", miniPicker(mavenExec, () ->
                context.chooseFile("选择 Maven 可执行文件", null, "maven.executable")), null));
        col1.getChildren().add(Widgets.field("settings.xml", miniPicker(settings, () ->
                context.chooseFile("选择 settings.xml",
                        List.of(new javafx.stage.FileChooser.ExtensionFilter("Maven Settings", "*.xml")),
                        "maven.settingsPath")), null));
        col1.getChildren().add(Widgets.field("Maven Profiles", profiles, null));
        col1.getChildren().add(Widgets.field("Maven 并行线程", parallel, "1–32；设置为 1 时不添加 -T"));
        VBox col2 = new VBox(8);
        col2.getChildren().add(Widgets.field("JDK 根目录（可选）", miniPicker(javaHome, () ->
                context.chooseDirectory("选择 JDK 根目录", "maven.javaHome")), null));
        col2.getChildren().add(Widgets.field("本地仓库", miniPicker(localRepo, () ->
                context.chooseDirectory("选择 Maven 本地仓库", "maven.localRepository")), null));
        col2.getChildren().add(Widgets.field("额外参数", extraArgs, null));
        col2.getChildren().add(Widgets.field("测试匹配（可选）", testPattern, null));
        grid.getChildren().addAll(col1, col2);
        panel.getChildren().add(grid);

        HBox thresholdRow = new HBox(14);
        thresholdRow.setAlignment(Pos.CENTER_LEFT);
        TextField jacocoVersion = textField(context.config.get().coverage.jacocoVersion, "");
        thresholdRow.getChildren().add(Widgets.field("JaCoCo", jacocoVersion, null));
        TextField lineThreshold = textField(String.valueOf(context.config.get().coverage.lineThreshold), "");
        thresholdRow.getChildren().add(Widgets.field("行覆盖率门槛（%）", lineThreshold, null));
        panel.getChildren().add(thresholdRow);

        mavenExec.textProperty().addListener((observable, oldValue, newValue) -> context.config.get().maven.executable = newValue);
        javaHome.textProperty().addListener((observable, oldValue, newValue) -> context.config.get().maven.javaHome = newValue);
        settings.textProperty().addListener((observable, oldValue, newValue) -> context.config.get().maven.settingsPath = newValue);
        localRepo.textProperty().addListener((observable, oldValue, newValue) -> context.config.get().maven.localRepository = newValue);
        profiles.textProperty().addListener((observable, oldValue, newValue) ->
                context.config.get().maven.profiles = splitList(newValue));
        extraArgs.textProperty().addListener((observable, oldValue, newValue) ->
                context.config.get().maven.extraArgs = splitList(newValue));
        parallel.textProperty().addListener((observable, oldValue, newValue) -> {
            try {
                context.config.get().maven.parallelThreads = Math.max(1, Math.min(32, Integer.parseInt(newValue.trim())));
            } catch (NumberFormatException error) {
                context.config.get().maven.parallelThreads = 1;
            }
        });
        testPattern.textProperty().addListener((observable, oldValue, newValue) -> context.config.get().maven.testPattern = newValue);
        jacocoVersion.textProperty().addListener((observable, oldValue, newValue) -> context.config.get().coverage.jacocoVersion = newValue);
        lineThreshold.textProperty().addListener((observable, oldValue, newValue) -> {
            try {
                context.config.get().coverage.lineThreshold = Integer.parseInt(newValue.trim());
            } catch (NumberFormatException error) {
                context.config.get().coverage.lineThreshold = 0;
            }
        });

        // 命令预览
        VBox commandBox = new VBox(6);
        commandBox.getStyleClass().add("command-box");
        Label previewCaption = new Label("命令预览");
        previewCaption.getStyleClass().add("form-label");
        previewCaption.setStyle("-fx-font-weight: bold;");
        Label previewDetail = Widgets.label("点击“生成预览”检查预构建及最终 Maven 命令", "form-hint");
        TextArea commandCode = new TextArea();
        commandCode.setEditable(false);
        commandCode.setPrefHeight(90);
        commandCode.setWrapText(false);
        commandCode.getStyleClass().add("command-code");
        Button previewButton = Widgets.button("生成预览", "text-button");
        previewButton.setOnAction(event -> context.previewCommand());
        context.preview.addListener((observable, oldValue, newValue) -> {
            if (newValue == null) {
                commandCode.setText("");
                previewDetail.setText("点击“生成预览”检查预构建及最终 Maven 命令");
                return;
            }
            StringBuilder text = new StringBuilder();
            if (newValue.preInstallArgs != null) {
                text.append("【首轮预构建】\n").append(newValue.executable).append(" ")
                        .append(String.join(" ", newValue.preInstallArgs)).append("\n\n");
            }
            text.append("【测试与覆盖率】\n").append(newValue.executable).append(" ")
                    .append(String.join(" ", newValue.args));
            commandCode.setText(text.toString());
            previewDetail.setText(sourceLabel(newValue) + " · " + (newValue.javaHome != null ? "使用指定/JAVA_HOME JDK" : "使用 PATH 中的 JDK"));
        });
        HBox previewHead = new HBox();
        previewHead.setAlignment(Pos.CENTER_LEFT);
        previewHead.getChildren().addAll(previewCaption, new Region() {{
            HBox.setHgrow(this, Priority.ALWAYS);
        }}, previewDetail);
        commandBox.getChildren().addAll(previewHead, commandCode, previewButton);
        panel.getChildren().add(commandBox);
        return panel;
    }

    private static List<String> splitList(String value) {
        List<String> result = new ArrayList<>();
        for (String item : value.trim().split("\\s+")) {
            if (!item.isEmpty()) result.add(item);
        }
        return result;
    }

    private static String sourceLabel(MavenCommandPreview preview) {
        switch (preview.source) {
            case "bundled": return "内置 Maven";
            case "configured": return "指定 Maven";
            case "wrapper": return "项目 Maven Wrapper";
            default: return "系统 Maven";
        }
    }

    private VBox buildAgentPanel() {
        VBox panel = new VBox(12);
        panel.getStyleClass().add("panel");
        AgentOptions agent = context.config.get().agent;
        panel.getChildren().add(Widgets.heading("Agent 与自动循环", "桌面程序直接调用 Agent，不再依赖 coverage_loop.py 或本机 Python",
                Widgets.label("AUTO LOOP", "tag")));

        ComboBox<String> agentSelect = new ComboBox<>();
        agentSelect.getItems().addAll("不启用", "Hermes", "OpenCode");
        agentSelect.setValue(agent.enabled ? (agent.provider.equals("hermes") ? "Hermes" : "OpenCode") : "不启用");
        agentSelect.setOnAction(event -> {
            String value = agentSelect.getValue();
            if ("不启用".equals(value)) {
                context.config.get().agent.enabled = false;
            } else {
                context.config.get().agent.enabled = true;
                context.config.get().agent.provider = "Hermes".equals(value) ? "hermes" : "opencode";
            }
            panel.getChildren().clear();
            panel.getChildren().addAll(buildAgentPanel().getChildren());
        });

        TextField maxRounds = textField(String.valueOf(agent.maxRounds), "");
        TextField model = textField(agent.model, "例如 deepseek_v4_flash");
        TextField executable = textField(agent.executable, agent.provider);
        TextField batchSize = textField(String.valueOf(agent.batchSize), "");
        TextField timeout = textField(String.valueOf(agent.timeoutMinutes), "");
        TextField maxSameFailures = textField(String.valueOf(agent.maxSameFailures), "");
        TextField heartbeat = textField(String.valueOf(agent.heartbeatSeconds), "");
        TextField hermesProvider = textField(agent.hermesProvider, "例如 custom");
        TextField opencodeAgent = textField(agent.opencodeAgent, "Agent 名称");
        TextField opencodeAttach = textField(agent.opencodeAttach, "http://localhost:4096");
        TextField agentExtraArgs = textField(String.join(" ", agent.extraArgs), "通常留空");

        HBox grid = new HBox(10);
        VBox col1 = new VBox(8);
        col1.getChildren().add(Widgets.field("Agent", agentSelect, null));
        col1.getChildren().add(Widgets.field("最大循环次数", maxRounds, "1–100"));
        col1.getChildren().add(Widgets.field("模型（可选）", model, null));
        col1.getChildren().add(Widgets.field("每轮处理类数", batchSize, "1–200"));
        col1.getChildren().add(Widgets.field("同一失败最多重复", maxSameFailures, "1–20"));
        VBox col2 = new VBox(8);
        col2.getChildren().add(Widgets.field("Agent 可执行文件（可选）", executable, null));
        col2.getChildren().add(Widgets.field("单轮超时（分钟）", timeout, "1–240"));
        col2.getChildren().add(Widgets.field("无输出心跳（秒）", heartbeat, "1–300"));
        if (agent.provider.equals("hermes")) {
            col2.getChildren().add(Widgets.field("Hermes Provider（可选）", hermesProvider, null));
        } else {
            col2.getChildren().add(Widgets.field("OpenCode Agent（可选）", opencodeAgent, null));
            col2.getChildren().add(Widgets.field("OpenCode Attach（可选）", opencodeAttach, null));
        }
        col2.getChildren().add(Widgets.field("Agent 额外参数", agentExtraArgs, null));
        grid.getChildren().addAll(col1, col2);
        panel.getChildren().add(grid);

        CheckBox autoApprove = new CheckBox("允许非交互编辑与命令");
        autoApprove.setSelected(agent.autoApprove);
        autoApprove.selectedProperty().addListener((observable, oldValue, newValue) ->
                context.config.get().agent.autoApprove = newValue);
        Label autoApproveHint = Widgets.label("Hermes 使用 --yolo，OpenCode 使用 --auto；关闭后 Agent 可能等待人工确认", "form-hint");
        panel.getChildren().add(toggleRow(autoApprove, autoApproveHint));

        CheckBox allowProduction = new CheckBox("允许有限生产代码重构");
        allowProduction.setSelected(agent.allowProductionChanges);
        allowProduction.selectedProperty().addListener((observable, oldValue, newValue) ->
                context.config.get().agent.allowProductionChanges = newValue);
        Label allowProductionHint = Widgets.label("默认关闭；开启后提示词仍禁止改变公共 API、业务行为、异常、事务与 Maven/JaCoCo 配置", "form-hint");
        panel.getChildren().add(toggleRow(allowProduction, allowProductionHint));

        // 提示词
        VBox promptBox = new VBox(6);
        promptBox.getStyleClass().add("panel");
        Label promptSummary = Widgets.label("▸ 补用例与修复用例提示词（点击展开）", "text-button");
        promptSummary.setOnMouseClicked(event -> {
            boolean visible = promptEditor != null && promptEditor.isVisible();
            if (promptEditor != null) {
                promptEditor.setVisible(!visible);
                promptEditor.setManaged(!visible);
            }
        });
        promptBox.getChildren().add(promptSummary);
        VBox editor = new VBox(8);
        editor.setVisible(false);
        editor.setManaged(false);
        promptEditor = editor;
        Label tokens = Widgets.label("支持占位符：{{round}}、{{projectRoot}}、{{moduleRoots}}、{{rootPom}}、{{mavenLog}}、{{surefireReports}}、{{coverageSnapshot}}、{{failedClasses}}、{{testCommand}}、{{targetClasses}}、{{batchSize}}、{{productionRule}}", "form-hint");
        tokens.setWrapText(true);
        editor.getChildren().add(tokens);
        TextArea coveragePrompt = new TextArea(agent.coveragePromptTemplate);
        coveragePrompt.setPrefHeight(200);
        coveragePrompt.setWrapText(false);
        coveragePrompt.textProperty().addListener((observable, oldValue, newValue) ->
                context.config.get().agent.coveragePromptTemplate = newValue);
        TextArea repairPrompt = new TextArea(agent.repairPromptTemplate);
        repairPrompt.setPrefHeight(180);
        repairPrompt.setWrapText(false);
        repairPrompt.textProperty().addListener((observable, oldValue, newValue) ->
                context.config.get().agent.repairPromptTemplate = newValue);
        editor.getChildren().add(Widgets.field("补用例提示词模板", coveragePrompt, null));
        editor.getChildren().add(Widgets.field("修复失败用例提示词模板", repairPrompt, null));
        promptBox.getChildren().add(editor);
        panel.getChildren().add(promptBox);

        maxRounds.textProperty().addListener((observable, oldValue, newValue) ->
                context.config.get().agent.maxRounds = parseClamped(newValue, 1, 100, 1));
        model.textProperty().addListener((observable, oldValue, newValue) -> context.config.get().agent.model = newValue);
        executable.textProperty().addListener((observable, oldValue, newValue) -> context.config.get().agent.executable = newValue);
        batchSize.textProperty().addListener((observable, oldValue, newValue) ->
                context.config.get().agent.batchSize = parseClamped(newValue, 1, 200, 1));
        timeout.textProperty().addListener((observable, oldValue, newValue) ->
                context.config.get().agent.timeoutMinutes = parseClamped(newValue, 1, 240, 1));
        maxSameFailures.textProperty().addListener((observable, oldValue, newValue) ->
                context.config.get().agent.maxSameFailures = parseClamped(newValue, 1, 20, 1));
        heartbeat.textProperty().addListener((observable, oldValue, newValue) ->
                context.config.get().agent.heartbeatSeconds = parseClamped(newValue, 1, 300, 1));
        hermesProvider.textProperty().addListener((observable, oldValue, newValue) ->
                context.config.get().agent.hermesProvider = newValue);
        opencodeAgent.textProperty().addListener((observable, oldValue, newValue) ->
                context.config.get().agent.opencodeAgent = newValue);
        opencodeAttach.textProperty().addListener((observable, oldValue, newValue) ->
                context.config.get().agent.opencodeAttach = newValue);
        agentExtraArgs.textProperty().addListener((observable, oldValue, newValue) ->
                context.config.get().agent.extraArgs = splitList(newValue));

        // 探活
        HBox probeRow = new HBox(10);
        probeRow.setAlignment(Pos.CENTER_LEFT);
        Button probeButton = Widgets.button("验证 Agent（回复 OK）", "button ghost");
        probeButton.setOnAction(event -> context.probeConfiguredAgent());
        Label probeStatus = Widgets.label("", "form-hint");
        context.agentProbe.addListener((observable, oldValue, newValue) -> {
            if (newValue == null) {
                probeStatus.setText("");
                return;
            }
            boolean passed = newValue.status == com.coverageloop.model.AgentExecutionStatus.passed;
            probeStatus.setText(passed ? "验证成功" : "验证失败：" + newValue.status.name());
            probeStatus.setStyle(passed ? "-fx-text-fill: #15803d;" : "-fx-text-fill: #b91c1c;");
        });
        Button probeLogButton = Widgets.textButton("打开探活日志");
        probeLogButton.setOnAction(event -> {
            AgentProbeResult probe = context.agentProbe.get();
            if (probe != null) context.openPath(probe.logPath);
        });
        probeRow.getChildren().addAll(probeButton, probeStatus, probeLogButton);
        panel.getChildren().add(probeRow);

        Label hint = Widgets.label("开始自动循环时会再次强制探活；未收到独立的 OK 回复就不会启动 Maven。"
                + "Maven 成功时使用“补用例”提示词，Maven/测试失败时使用“修复用例”提示词；同一失败达到上限后停止。", "form-hint");
        hint.setWrapText(true);
        panel.getChildren().add(hint);

        context.running.addListener((observable, oldValue, newValue) -> {
            probeButton.setDisable(newValue || !context.config.get().agent.enabled);
            panel.setDisable(newValue);
        });
        return panel;
    }

    private VBox promptEditor;

    private int parseClamped(String value, int min, int max, int fallback) {
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(value.trim())));
        } catch (NumberFormatException error) {
            return fallback;
        }
    }

    private HBox toggleRow(CheckBox checkBox, Label hint) {
        HBox row = new HBox(8);
        row.setAlignment(Pos.CENTER_LEFT);
        VBox text = new VBox(0);
        text.getChildren().add(checkBox);
        if (hint != null) text.getChildren().add(hint);
        row.getChildren().add(text);
        return row;
    }

    private HBox miniPicker(TextField field, Runnable browse) {
        HBox box = new HBox(4);
        HBox.setHgrow(field, Priority.ALWAYS);
        Button button = Widgets.button("…", "button ghost");
        button.setOnAction(event -> browse.run());
        box.getChildren().addAll(field, button);
        return box;
    }

    private TextField textField(String value, String prompt) {
        TextField field = new TextField(value == null ? "" : value);
        if (prompt != null && !prompt.isEmpty()) field.setPromptText(prompt);
        return field;
    }

    // ---------- 结果区 ----------

    private void refreshResults() {
        resultsBox.getChildren().clear();
        MavenRunResult result = context.runResult.get();
        if (result != null) resultsBox.getChildren().add(buildTestResultPanel(result));
        CoverageLoopResult loop = context.loopResult.get();
        AgentRoundResult latestAgent = loop != null && !loop.agentRounds.isEmpty()
                ? loop.agentRounds.get(loop.agentRounds.size() - 1) : null;
        if (latestAgent != null) resultsBox.getChildren().add(buildAgentRoundPanel(latestAgent));
        if (result != null && !result.coverage.isEmpty()) {
            resultsBox.getChildren().add(buildCoverageCards(result));
            resultsBox.getChildren().add(buildScopeResultsPanel(result));
            resultsBox.getChildren().add(buildGroupPanel(result));
        }
    }

    private Node buildTestResultPanel(MavenRunResult result) {
        VBox panel = new VBox(10);
        panel.getStyleClass().add("panel");
        TestExecutionStatus status = result.tests.status;
        panel.getChildren().add(Widgets.heading("测试执行结果", result.tests.message,
                Widgets.pill(status.label(), status == TestExecutionStatus.passed ? "green"
                        : status == TestExecutionStatus.test_failed ? "gray" : "blue")));

        if (result.preInstall.attempted) {
            boolean ok = result.preInstall.exitCode != null && result.preInstall.exitCode == 0;
            Label preInstallLabel = Widgets.label("首轮预构建：" + (ok ? "skip tests install 成功"
                    : "失败（退出码 " + result.preInstall.exitCode + "）"), "form-hint");
            preInstallLabel.setStyle(ok ? "-fx-text-fill: #15803d;" : "-fx-text-fill: #b91c1c;");
            panel.getChildren().add(preInstallLabel);
        }

        HBox summary = new HBox(20);
        int noTestModules = 0;
        for (var module : result.tests.modules) {
            if (module.status == com.coverageloop.model.TestModuleStatus.no_tests
                    || module.status == com.coverageloop.model.TestModuleStatus.unknown) {
                noTestModules += 1;
            }
        }
        summary.getChildren().add(metricMini("执行", String.valueOf(result.tests.tests)));
        summary.getChildren().add(metricMini("失败", String.valueOf(result.tests.failures + result.tests.errors)));
        summary.getChildren().add(metricMini("跳过", String.valueOf(result.tests.skipped)));
        summary.getChildren().add(metricMini("无报告模块", String.valueOf(noTestModules)));
        panel.getChildren().add(summary);

        if (result.tests.reportArchivePath != null) {
            Button openSurefire = Widgets.textButton("打开本轮 Surefire 报告");
            openSurefire.setOnAction(event -> context.openPath(result.tests.reportArchivePath));
            panel.getChildren().add(openSurefire);
        }
        return panel;
    }

    private VBox metricMini(String labelText, String value) {
        VBox box = new VBox(0);
        Label label = Widgets.label(labelText, "form-hint");
        Label valueLabel = Widgets.label(value, "metric-value");
        box.getChildren().addAll(label, valueLabel);
        return box;
    }

    private Node buildAgentRoundPanel(AgentRoundResult round) {
        VBox panel = new VBox(10);
        panel.getStyleClass().add("panel");
        String modeText = "repair".equals(round.mode) ? "修复失败用例" : "补充覆盖率用例";
        panel.getChildren().add(Widgets.heading("最近一次 Agent 执行",
                "第 " + round.round + " 轮 · " + modeText + " · 修改 " + round.changedTestFiles.size() + " 个测试文件",
                Widgets.pill(round.status.name(),
                        round.status == com.coverageloop.model.AgentExecutionStatus.passed ? "green" : "gray")));
        HBox links = new HBox(8);
        Button promptButton = Widgets.textButton("打开本轮提示词");
        promptButton.setOnAction(event -> context.openPath(round.promptPath));
        Button logButton = Widgets.textButton("打开 Agent 日志");
        logButton.setOnAction(event -> context.openPath(round.logPath));
        Button changedButton = Widgets.textButton("打开测试变更清单");
        changedButton.setOnAction(event -> context.openPath(round.changedTestsPath));
        links.getChildren().addAll(promptButton, logButton, changedButton);
        if (round.failedClassesPath != null) {
            Button failedButton = Widgets.textButton("打开待补类清单");
            failedButton.setOnAction(event -> context.openPath(round.failedClassesPath));
            links.getChildren().add(failedButton);
        }
        panel.getChildren().add(links);
        Label marker = Widgets.label(round.completionMarkerSeen
                ? "完成标记：已收到 BATCH_COMPLETE" : "完成标记：未收到；代码修改和日志仍已保留", "form-hint");
        panel.getChildren().add(marker);
        return panel;
    }

    private Node buildCoverageCards(MavenRunResult result) {
        HBox cards = new HBox(12);
        cards.setAlignment(Pos.CENTER_LEFT);
        for (CoverageModuleResult module : result.coverage) {
            boolean passed = (module.lineCoverage != null ? module.lineCoverage : 0)
                    >= context.config.get().coverage.lineThreshold;
            VBox card = new VBox(2);
            card.getStyleClass().addAll("result-card", passed ? "passed" : "failed");
            Label status = Widgets.label(passed ? "达标" : "未达标", "result-status");
            status.getStyleClass().add("result-status");
            Label value = Widgets.label(Text.coverageText(module.lineCoverage), "result-value");
            value.getStyleClass().add("result-value");
            Label sub = Widgets.label(module.modulePath + " · " + module.classCount + " 类 · "
                    + module.coveredLines + "/" + (module.coveredLines + module.missedLines) + " 行 · "
                    + ("jacoco".equals(module.source) ? "JaCoCo" : "无报告，源码按 0% 兜底"), "result-sub");
            sub.getStyleClass().add("result-sub");
            sub.setWrapText(true);
            card.getChildren().addAll(status, value, sub);
            cards.getChildren().add(card);
        }
        return cards;
    }

    private Node buildScopeResultsPanel(MavenRunResult result) {
        VBox panel = new VBox(10);
        panel.getStyleClass().add("panel");
        panel.getChildren().add(Widgets.heading("按配置目录统计", "按 Include 范围分别汇总，Exclude 已优先扣除",
                Widgets.pill("第 " + result.round + " 轮", "blue")));
        List<CoverageScopeResult> scopeResults = new ArrayList<>();
        for (CoverageModuleResult module : result.coverage) scopeResults.addAll(module.scopeResults);
        for (CoverageScopeResult item : scopeResults) {
            HBox row = new HBox(10);
            row.setAlignment(Pos.CENTER_LEFT);
            VBox text = new VBox(0);
            Label pattern = Widgets.label(item.pattern, "form-label");
            pattern.setStyle("-fx-font-weight: bold;");
            String kindText = "module".equals(item.kind) ? "模块全部范围"
                    : "package".equals(item.kind) ? "包目录" : "类";
            Label sub = Widgets.label(item.modulePath + " · " + kindText, "form-hint");
            text.getChildren().addAll(pattern, sub);
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            Label count = Widgets.label(String.valueOf(item.classCount) + " 类", "form-hint");
            Label lines = Widgets.label(item.coveredLines + "/" + (item.coveredLines + item.missedLines), "form-hint");
            Label coverage = Widgets.label(Text.coverageText(item.lineCoverage), "form-label");
            coverage.setStyle("-fx-font-weight: bold; -fx-text-fill: #4f46e5;");
            row.getChildren().addAll(text, spacer, count, lines, coverage);
            panel.getChildren().add(row);
        }
        return panel;
    }

    private Node buildGroupPanel(MavenRunResult result) {
        VBox panel = new VBox(10);
        panel.getStyleClass().add("panel");
        HBox heading = Widgets.heading("类级覆盖率分组", "首次结果固定为基线，后续轮次只更新当前覆盖率");
        Button openDir = Widgets.button("打开日志目录", "button ghost");
        openDir.setOnAction(event -> context.openPath(result.runDirectory));
        heading.getChildren().add(openDir);
        panel.getChildren().add(heading);

        HBox tabs = new HBox(8);
        tabs.getStyleClass().add("group-tabs");
        String[][] groups = {{"initialSatisfied", "初始已满足"}, {"pending", "待补充"}, {"supplemented", "已补充"}};
        for (String[] group : groups) {
            Button tab = Widgets.button("", "group-tab");
            Label text = Widgets.label(group[1], "group-tab-text");
            Label count = Widgets.label("0", "count");
            HBox content = new HBox(6);
            content.setAlignment(Pos.CENTER);
            content.getChildren().addAll(text, count);
            tab.setGraphic(content);
            tab.setOnAction(event -> {
                selectedGroup = group[0];
                rebuildGroupList(panel, result, group[0]);
                for (Node child : tabs.getChildren()) {
                    child.getStyleClass().setAll("group-tab");
                }
                tab.getStyleClass().add("active");
            });
            tabs.getChildren().add(tab);
        }
        panel.getChildren().add(tabs);

        VBox groupList = new VBox(6);
        groupList.setId("group-list");
        panel.getChildren().add(groupList);
        rebuildGroupList(panel, result, selectedGroup);
        for (Node child : tabs.getChildren()) {
            if (child instanceof Button button && button.getText().equals("")) {
                String label = ((Label) ((HBox) button.getGraphic()).getChildren().get(0)).getText();
                if (("初始已满足".equals(label) && selectedGroup.equals("initialSatisfied"))
                        || ("待补充".equals(label) && selectedGroup.equals("pending"))
                        || ("已补充".equals(label) && selectedGroup.equals("supplemented"))) {
                    button.getStyleClass().add("active");
                }
            }
        }
        return panel;
    }

    private void rebuildGroupList(VBox panel, MavenRunResult result, String key) {
        VBox groupList = null;
        for (Node child : panel.getChildren()) {
            if ("group-list".equals(child.getId())) groupList = (VBox) child;
        }
        if (groupList == null) return;
        groupList.getChildren().clear();
        List<CoverageGroupedClass> items = switch (key) {
            case "initialSatisfied" -> result.groups.initialSatisfied;
            case "supplemented" -> result.groups.supplemented;
            default -> result.groups.pending;
        };
        int shown = Math.min(items.size(), 300);
        for (int i = 0; i < shown; i++) {
            CoverageGroupedClass item = items.get(i);
            HBox row = new HBox(10);
            row.getStyleClass().add("coverage-class-row");
            row.setAlignment(Pos.CENTER_LEFT);
            VBox text = new VBox(0);
            Label name = Widgets.label(item.className, "cc-name");
            name.getStyleClass().add("cc-name");
            Label sub = Widgets.label(item.modulePath + " · " + item.qualifiedName, "cc-sub");
            sub.getStyleClass().add("cc-sub");
            text.getChildren().addAll(name, sub);
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            Label initial = Widgets.label("初始 " + Text.coverageText(item.initialLineCoverage), "form-hint");
            Label current = Widgets.label(Text.coverageText(item.currentLineCoverage), "cc-value");
            current.getStyleClass().add("cc-value");
            row.getChildren().addAll(text, spacer, initial, current);
            groupList.getChildren().add(row);
        }
        if (items.size() > 300) {
            Label note = Widgets.label("当前显示前 300 个类，完整结果已保存在本轮覆盖率快照中", "form-hint");
            groupList.getChildren().add(note);
        }
    }

    // ---------- 控制台 ----------

    private void refreshConsoleState() {
        boolean isRunning = context.running.get();
        MavenRunResult result = context.runResult.get();
        CoverageLoopResult loop = context.loopResult.get();
        actionsBox.getChildren().clear();
        statusPill.getStyleClass().setAll("status-pill", isRunning ? "running" : "");
        statusPill.setText(isRunning ? "RUNNING" : "READY");
        if (loop != null) {
            consoleCaption.setText(loop.message);
        } else if (result != null) {
            consoleCaption.setText("执行 " + result.runId + " · 第 " + result.round
                    + (context.config.get().agent.enabled ? " / " + context.config.get().agent.maxRounds : "") + " 轮");
        } else if (isRunning) {
            consoleCaption.setText("Agent / Maven 正在运行");
        } else {
            consoleCaption.setText("等待执行");
        }

        if (isRunning) {
            Button stop = Widgets.button("停止任务", "button danger");
            stop.setMaxWidth(Double.MAX_VALUE);
            stop.setOnAction(event -> context.stopMaven());
            actionsBox.getChildren().add(stop);
        } else if (context.config.get().agent.enabled) {
            Button loopButton = Widgets.button("探活并开始新的自动循环（最多 " + context.config.get().agent.maxRounds + " 轮）", "button primary");
            loopButton.setMaxWidth(Double.MAX_VALUE);
            loopButton.setOnAction(event -> context.runAutomaticLoop());
            Button mavenButton = Widgets.button(result != null ? "仅执行第 " + (result.round + 1) + " 轮 Maven" : "仅建立基线并执行 Maven", "button secondary");
            mavenButton.setMaxWidth(Double.MAX_VALUE);
            mavenButton.setDisable(result != null && result.round >= context.config.get().agent.maxRounds);
            mavenButton.setOnAction(event -> context.runMaven());
            actionsBox.getChildren().addAll(loopButton, mavenButton);
            if (result != null) {
                Button clear = Widgets.button("清除当前结果", "button ghost");
                clear.setMaxWidth(Double.MAX_VALUE);
                clear.setOnAction(event -> context.startNewBaseline());
                actionsBox.getChildren().add(clear);
            }
        } else {
            Button mavenButton = Widgets.button(result != null ? "执行第 " + (result.round + 1) + " 轮" : "建立基线并执行第 1 轮", "button primary");
            mavenButton.setMaxWidth(Double.MAX_VALUE);
            mavenButton.setOnAction(event -> context.runMaven());
            actionsBox.getChildren().add(mavenButton);
            if (result != null) {
                Button newBaseline = Widgets.button("新建一组基线", "button secondary");
                newBaseline.setMaxWidth(Double.MAX_VALUE);
                newBaseline.setOnAction(event -> context.startNewBaseline());
                actionsBox.getChildren().add(newBaseline);
            }
        }
    }

    private void updateProgress(MavenProgressEvent event) {
        if (event == null) {
            progressMessage.setText("尚未开始");
            progressPercent.setText("0%");
            progressFill.setPrefWidth(0);
            return;
        }
        progressMessage.setText(event.message);
        progressPercent.setText(event.percent + "%");
        double trackWidth = progressTrack.getWidth();
        progressFill.setPrefWidth(trackWidth > 0 ? trackWidth * event.percent / 100.0 : 0);
    }
}
