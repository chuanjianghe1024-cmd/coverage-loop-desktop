package com.coverageloop.ui;

import com.coverageloop.model.CoverageClassResult;
import com.coverageloop.model.CoverageScope;
import com.coverageloop.model.CoverageStatisticsConfig;
import com.coverageloop.model.CoverageStatisticsGroup;
import com.coverageloop.model.CoverageStatisticsGroupResult;
import com.coverageloop.model.CoverageStatisticsModuleResult;
import com.coverageloop.model.CoverageStatisticsPackageResult;
import com.coverageloop.model.CoverageStatisticsSnapshotInfo;
import com.coverageloop.model.CoverageStatisticsSummary;
import com.coverageloop.model.JavaClassInfo;
import com.coverageloop.model.MavenModule;
import com.coverageloop.model.ModuleSources;
import com.coverageloop.model.ProjectConfig;
import com.coverageloop.model.ScopeKind;
import com.coverageloop.model.ScopeMode;
import com.coverageloop.service.ScopeSelection;
import com.coverageloop.util.Text;
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
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** 视图 Σ：覆盖率统计（分组 + 模块/包/类范围 + 明细树） */
public class StatisticsView {

    private final AppContext context;
    private final ComboBox<String> groupSelect = new ComboBox<>();
    private final TextField groupName = new TextField();
    private final VBox moduleList = new VBox(6);
    private final ComboBox<String> moduleSelect = new ComboBox<>();
    private final TextField searchField = new TextField();
    private final VBox treeBox = new VBox(2);
    private final VBox rulesBox = new VBox(6);
    private final Label sourceStats = new Label();
    private final VBox summaryBox = new VBox(6);
    private final Label summaryHead = new Label();
    private final TextArea console = new TextArea();
    private final Set<String> expanded = new HashSet<>();
    private String activeGroupId = "";

    public StatisticsView(AppContext context) {
        this.context = context;
    }

    public Node build() {
        HBox layout = new HBox(14);
        layout.setPrefWidth(1280);

        // 左列：分组与范围配置
        VBox left = new VBox(14);
        left.setMaxWidth(760);
        HBox.setHgrow(left, Priority.ALWAYS);

        VBox groupPanel = new VBox(12);
        groupPanel.getStyleClass().add("panel");
        groupPanel.getChildren().add(Widgets.heading("统计分组", "每组可跨选多个模块，并配置包/类范围"));
        HBox groupControls = new HBox(8);
        groupControls.setAlignment(Pos.CENTER_LEFT);
        groupSelect.setPrefWidth(200);
        Button addGroup = Widgets.button("新增分组", "button ghost");
        addGroup.setOnAction(event -> addGroup());
        Button deleteGroup = Widgets.button("删除分组", "button ghost");
        deleteGroup.setOnAction(event -> deleteGroup());
        groupControls.getChildren().addAll(groupSelect, groupName, addGroup, deleteGroup);
        groupPanel.getChildren().add(groupControls);
        Label groupHint = Widgets.label("勾选分组模块；确认分组后统计结果会基于最近一次覆盖率快照重算", "form-hint");
        groupPanel.getChildren().add(groupHint);
        moduleList.setMaxHeight(240);
        ScrollPane moduleScroll = new ScrollPane(moduleList);
        moduleScroll.setFitToWidth(true);
        moduleScroll.setPrefHeight(210);
        groupPanel.getChildren().add(moduleScroll);
        left.getChildren().add(groupPanel);

        VBox scopePanel = new VBox(12);
        scopePanel.getStyleClass().add("panel");
        scopePanel.getChildren().add(Widgets.heading("分组范围", "Exclude 优先于 Include；不选 Include 表示包含全部"));
        HBox controls = new HBox(10);
        controls.setAlignment(Pos.CENTER_LEFT);
        moduleSelect.setPrefWidth(300);
        searchField.setPromptText("搜索包或类");
        searchField.setPrefWidth(200);
        controls.getChildren().addAll(moduleSelect, searchField);
        scopePanel.getChildren().add(controls);
        ScrollPane treeScroll = new ScrollPane(treeBox);
        treeScroll.setFitToWidth(true);
        treeScroll.setPrefHeight(320);
        scopePanel.getChildren().add(treeScroll);
        sourceStats.getStyleClass().add("form-hint");
        scopePanel.getChildren().add(sourceStats);
        scopePanel.getChildren().add(rulesBox);
        left.getChildren().add(scopePanel);

        HBox actions = new HBox(10);
        actions.setAlignment(Pos.CENTER_RIGHT);
        Label parallelLabel = Widgets.label("并行线程", "form-label");
        TextField parallelField = new TextField(String.valueOf(context.config.get().maven.parallelThreads));
        parallelField.setPrefWidth(70);
        parallelField.textProperty().addListener((observable, oldValue, newValue) -> {
            try {
                context.config.get().maven.parallelThreads = Math.max(1, Math.min(32, Integer.parseInt(newValue.trim())));
            } catch (NumberFormatException error) {
                context.config.get().maven.parallelThreads = 1;
            }
        });
        Button saveButton = Widgets.button("保存草稿", "button ghost");
        saveButton.setOnAction(event -> context.saveStatistics());
        Button confirmButton = Widgets.button("确认分组", "button secondary");
        confirmButton.setOnAction(event -> {
            CoverageStatisticsConfig value = context.statisticsConfig.get();
            if (value != null) context.confirmStatistics(value);
        });
        Button runButton = Widgets.button("运行选定模块 Test", "button primary");
        runButton.setOnAction(event -> {
            CoverageStatisticsConfig value = context.statisticsConfig.get();
            if (value != null) context.runCoverageStatistics(value);
        });
        actions.getChildren().addAll(parallelLabel, parallelField, Widgets.hgap(10),
                saveButton, confirmButton, runButton);
        left.getChildren().add(actions);

        // 右列：统计结果 + 控制台
        VBox right = new VBox(14);
        right.setPrefWidth(430);
        VBox summaryPanel = new VBox(10);
        summaryPanel.getStyleClass().add("panel");
        summaryHead.getStyleClass().add("panel-title");
        summaryPanel.getChildren().add(summaryHead);
        ScrollPane summaryScroll = new ScrollPane(summaryBox);
        summaryScroll.setFitToWidth(true);
        summaryScroll.setPrefHeight(400);
        summaryPanel.getChildren().add(summaryScroll);
        right.getChildren().add(summaryPanel);

        VBox consolePanel = new VBox(10);
        consolePanel.getStyleClass().add("panel");
        consolePanel.getChildren().add(Widgets.heading("执行日志", "统计模式固定追加 -Dmaven.test.failure.ignore=true"));
        console.setEditable(false);
        console.getStyleClass().add("console");
        console.textProperty().bind(context.logs);
        console.setPrefHeight(300);
        consolePanel.getChildren().add(console);
        right.getChildren().add(consolePanel);

        layout.getChildren().addAll(left, right);

        // 事件
        groupSelect.valueProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue != null) {
                activeGroupId = newValue;
                refreshGroupEditor();
            }
        });
        groupName.textProperty().addListener((observable, oldValue, newValue) -> {
            CoverageStatisticsGroup group = activeGroup();
            if (group != null && newValue != null) {
                updateGroup(g -> g.name = newValue);
            }
        });
        moduleSelect.valueProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue != null) {
                CoverageStatisticsGroup group = activeGroup();
                if (group != null && !group.modulePaths.contains(newValue)) {
                    updateGroup(g -> g.modulePaths = new ArrayList<>(g.modulePaths) {{
                        add(newValue);
                    }});
                }
                refreshScopeTree();
            }
        });
        searchField.textProperty().addListener((observable, oldValue, newValue) -> refreshScopeTree());
        context.statisticsConfig.addListener((observable, oldValue, newValue) -> {
            refreshGroupSelect();
            refreshGroupEditor();
            refreshSummary();
        });
        context.statisticsSummary.addListener((observable, oldValue, newValue) -> refreshSummary());
        context.statisticsSnapshot.addListener((observable, oldValue, newValue) -> refreshSummary());
        context.activeSources.addListener((observable, oldValue, newValue) -> refreshScopeTree());

        refreshGroupSelect();
        refreshGroupEditor();
        refreshSummary();
        return layout;
    }

    private CoverageStatisticsGroup activeGroup() {
        CoverageStatisticsConfig config = context.statisticsConfig.get();
        if (config == null) return null;
        for (CoverageStatisticsGroup group : config.groups) {
            if (group.id.equals(activeGroupId)) return group;
        }
        return config.groups.isEmpty() ? null : config.groups.get(0);
    }

    private void updateGroup(java.util.function.Consumer<CoverageStatisticsGroup> updater) {
        CoverageStatisticsConfig config = context.statisticsConfig.get();
        CoverageStatisticsGroup group = activeGroup();
        if (config == null || group == null) return;
        boolean wasConfirmed = group.confirmedAt != null;
        CoverageStatisticsConfig copy = new CoverageStatisticsConfig();
        copy.schemaVersion = config.schemaVersion;
        copy.rootPomPath = config.rootPomPath;
        copy.updatedAt = config.updatedAt;
        List<CoverageStatisticsGroup> groups = new ArrayList<>();
        for (CoverageStatisticsGroup item : config.groups) {
            CoverageStatisticsGroup groupCopy = new CoverageStatisticsGroup();
            groupCopy.id = item.id;
            groupCopy.name = item.name;
            groupCopy.modulePaths = new ArrayList<>(item.modulePaths);
            groupCopy.scopes = new ArrayList<>(item.scopes);
            groupCopy.confirmedAt = item.confirmedAt;
            if (item.id.equals(group.id)) {
                updater.accept(groupCopy);
                groupCopy.confirmedAt = null;
            }
            groups.add(groupCopy);
        }
        copy.groups = groups;
        context.statisticsConfig.set(copy);
        if (wasConfirmed) context.statisticsSummary.set(null);
    }

    private void addGroup() {
        CoverageStatisticsConfig config = context.statisticsConfig.get();
        if (config == null) return;
        CoverageStatisticsConfig copy = new CoverageStatisticsConfig();
        copy.schemaVersion = config.schemaVersion;
        copy.rootPomPath = config.rootPomPath;
        copy.updatedAt = config.updatedAt;
        copy.groups = new ArrayList<>(config.groups);
        CoverageStatisticsGroup group = new CoverageStatisticsGroup();
        group.id = "group-" + System.currentTimeMillis();
        group.name = "分组 " + (config.groups.size() + 1);
        copy.groups.add(group);
        context.statisticsConfig.set(copy);
        activeGroupId = group.id;
        refreshGroupSelect();
        refreshGroupEditor();
    }

    private void deleteGroup() {
        CoverageStatisticsConfig config = context.statisticsConfig.get();
        CoverageStatisticsGroup group = activeGroup();
        if (config == null || group == null || config.groups.size() <= 1) return;
        CoverageStatisticsConfig copy = new CoverageStatisticsConfig();
        copy.schemaVersion = config.schemaVersion;
        copy.rootPomPath = config.rootPomPath;
        copy.updatedAt = config.updatedAt;
        copy.groups = new ArrayList<>();
        for (CoverageStatisticsGroup item : config.groups) {
            if (!item.id.equals(group.id)) copy.groups.add(item);
        }
        context.statisticsConfig.set(copy);
        activeGroupId = copy.groups.isEmpty() ? "" : copy.groups.get(0).id;
        refreshGroupSelect();
        refreshGroupEditor();
    }

    private void refreshGroupSelect() {
        CoverageStatisticsConfig config = context.statisticsConfig.get();
        if (config == null) return;
        String previous = activeGroupId;
        groupSelect.getItems().clear();
        for (CoverageStatisticsGroup group : config.groups) {
            groupSelect.getItems().add(group.id + " · " + group.name);
        }
        if (!config.groups.isEmpty()) {
            boolean exists = config.groups.stream().anyMatch(g -> g.id.equals(previous));
            activeGroupId = exists ? previous : config.groups.get(0).id;
            for (CoverageStatisticsGroup group : config.groups) {
                if (group.id.equals(activeGroupId)) {
                    groupSelect.setValue(group.id + " · " + group.name);
                }
            }
        }
    }

    private void refreshGroupEditor() {
        CoverageStatisticsGroup group = activeGroup();
        if (group == null) return;
        groupName.setText(group.name);
        // 模块列表
        moduleList.getChildren().clear();
        ProjectConfig config = context.config.get();
        if (config != null && context.project.get() != null) {
            for (MavenModule module : context.project.get().modules) {
                if (!module.hasMainSources) continue;
                CheckBox checkBox = new CheckBox(module.label());
                checkBox.setSelected(group.modulePaths.contains(module.relativePath));
                checkBox.setOnAction(event -> updateGroup(g -> {
                    List<String> paths = new ArrayList<>(g.modulePaths);
                    if (checkBox.isSelected()) {
                        if (!paths.contains(module.relativePath)) paths.add(module.relativePath);
                    } else {
                        paths.remove(module.relativePath);
                    }
                    g.modulePaths = paths;
                }));
                moduleList.getChildren().add(checkBox);
            }
        }
        // 模块选择
        String previous = moduleSelect.getValue();
        moduleSelect.getItems().clear();
        for (String modulePath : group.modulePaths) {
            for (MavenModule module : context.project.get().modules) {
                if (module.relativePath.equals(modulePath)) moduleSelect.getItems().add(module.label());
            }
        }
        if (!group.modulePaths.isEmpty()) {
            moduleSelect.setValue(previous != null && moduleSelect.getItems().contains(previous)
                    ? previous : moduleSelect.getItems().get(0));
        }
        refreshScopeTree();
        refreshRules();
    }

    private String activeModulePath() {
        CoverageStatisticsGroup group = activeGroup();
        if (group == null || group.modulePaths.isEmpty()) return null;
        String value = moduleSelect.getValue();
        if (value == null) return group.modulePaths.get(0);
        for (MavenModule module : context.project.get().modules) {
            if (module.label().equals(value)) return module.relativePath;
        }
        return group.modulePaths.get(0);
    }

    private List<CoverageScope> activeModuleScopes() {
        CoverageStatisticsGroup group = activeGroup();
        String modulePath = activeModulePath();
        List<CoverageScope> scopes = new ArrayList<>();
        if (group == null || modulePath == null) return scopes;
        for (CoverageScope scope : group.scopes) {
            if (scope.modulePath.equals(modulePath)) scopes.add(scope);
        }
        return scopes;
    }

    private void refreshScopeTree() {
        treeBox.getChildren().clear();
        String modulePath = activeModulePath();
        if (modulePath == null) return;
        MavenModule module = null;
        for (MavenModule candidate : context.project.get().modules) {
            if (candidate.relativePath.equals(modulePath)) module = candidate;
        }
        if (module == null) return;
        ModuleSources sources = context.sourceCache.get(modulePath);
        if (sources == null) {
            treeBox.getChildren().add(Widgets.label("正在扫描 Java 源码…", "empty-state"));
            context.loadModuleSources(module);
            return;
        }
        List<CoverageScope> scopes = activeModuleScopes();
        String keyword = searchField.getText().trim().toLowerCase();
        boolean searching = !keyword.isEmpty();
        for (String packageName : sources.packages) {
            if (packageName.split("\\.").length <= 2) expanded.add(packageName);
        }
        // 包行
        Set<String> renderedPackages = new LinkedHashSet<>();
        for (JavaClassInfo item : sources.classes) {
            renderedPackages.add(item.packageName);
        }
        List<String> packageList = new ArrayList<>(renderedPackages);
        packageList.sort(String::compareTo);
        for (String packageName : packageList) {
            if (searching && !packageName.toLowerCase().contains(keyword)) continue;
            renderStatPackageRow(packageName, scopes, searching, keyword, sources);
        }
        // 默认包类
        for (JavaClassInfo item : sources.classes) {
            if (!item.packageName.isEmpty()) continue;
            if (searching && !item.qualifiedName.toLowerCase().contains(keyword)) continue;
            renderStatClassRow(item, scopes);
        }
        int[] counts = ScopeSelection.countSelectedFiles(scopes, sources.classes, sources.testClasses);
        sourceStats.setText("选中 " + counts[0] + " 个业务文件 / " + sources.sourceFileCount
                + "，测试文件 " + counts[1] + " / " + sources.testFileCount);
    }

    private void renderStatPackageRow(String packageName, List<CoverageScope> scopes,
                                      boolean searching, String keyword, ModuleSources sources) {
        HBox row = new HBox(6);
        row.getStyleClass().add("stat-row");
        row.setAlignment(Pos.CENTER_LEFT);
        boolean open = searching || expanded.contains(packageName);
        Button expander = new Button(open ? "⌄" : "›");
        expander.getStyleClass().add("tree-expander");
        expander.setOnAction(event -> {
            if (expanded.contains(packageName)) expanded.remove(packageName);
            else expanded.add(packageName);
            refreshScopeTree();
        });
        Label symbol = Widgets.label("P", "tree-symbol");
        VBox nameBox = new VBox(0);
        Label name = Widgets.label(packageName, "stat-name bold");
        name.getStyleClass().addAll("stat-name", "bold");
        nameBox.getChildren().add(name);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox scopeButtons = statScopeButtons(scopes, ScopeKind.package_, packageName);
        row.getChildren().addAll(expander, symbol, nameBox, spacer, scopeButtons);
        treeBox.getChildren().add(row);
        if (!open) return;
        // 子包与类
        for (JavaClassInfo item : sources.classes) {
            if (!item.packageName.equals(packageName)) continue;
            if (item.packageName.split("\\.").length > 1) continue; // 子包另行处理
            if (searching && !item.qualifiedName.toLowerCase().contains(keyword)) continue;
            renderStatClassRow(item, scopes);
        }
        // 子包（前缀匹配，简化展示一层）
        Set<String> subPackages = new LinkedHashSet<>();
        for (JavaClassInfo item : sources.classes) {
            if (item.packageName.startsWith(packageName + ".")) {
                String rest = item.packageName.substring(packageName.length() + 1);
                String firstSegment = rest.split("\\.")[0];
                subPackages.add(packageName + "." + firstSegment);
            }
        }
        List<String> subList = new ArrayList<>(subPackages);
        subList.sort(String::compareTo);
        for (String sub : subList) {
            if (searching && !sub.toLowerCase().contains(keyword)) continue;
            HBox subRow = new HBox(6);
            subRow.getStyleClass().add("stat-row");
            subRow.setAlignment(Pos.CENTER_LEFT);
            subRow.setPadding(new javafx.geometry.Insets(4, 8, 4, 32));
            Label subSymbol = Widgets.label("P", "tree-symbol");
            Label subName = Widgets.label(sub, "stat-name");
            subName.getStyleClass().add("stat-name");
            Region subSpacer = new Region();
            HBox.setHgrow(subSpacer, Priority.ALWAYS);
            HBox subButtons = statScopeButtons(scopes, ScopeKind.package_, sub);
            subRow.getChildren().addAll(Widgets.hgap(18), subSymbol, subName, subSpacer, subButtons);
            treeBox.getChildren().add(subRow);
            for (JavaClassInfo item : sources.classes) {
                if (!item.packageName.equals(sub)) continue;
                if (searching && !item.qualifiedName.toLowerCase().contains(keyword)) continue;
                renderStatClassRow(item, scopes);
            }
        }
    }

    private void renderStatClassRow(JavaClassInfo item, List<CoverageScope> scopes) {
        HBox row = new HBox(6);
        row.getStyleClass().add("stat-row");
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new javafx.geometry.Insets(4, 8, 4, 46));
        Label connector = Widgets.label("└", "tree-connector");
        connector.setStyle("-fx-text-fill: #c3c8d4;");
        Label symbol = Widgets.label("C", "tree-symbol clazz");
        VBox nameBox = new VBox(0);
        Label name = Widgets.label(item.name, "stat-name");
        name.getStyleClass().add("stat-name");
        Label sub = Widgets.label(item.qualifiedName, "stat-meta");
        sub.getStyleClass().add("stat-meta");
        nameBox.getChildren().addAll(name, sub);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox scopeButtons = statScopeButtons(scopes, ScopeKind.class_, item.qualifiedName);
        row.getChildren().addAll(connector, symbol, nameBox, spacer, scopeButtons);
        treeBox.getChildren().add(row);
    }

    private HBox statScopeButtons(List<CoverageScope> scopes, ScopeKind kind, String pattern) {
        CoverageScope existing = null;
        for (CoverageScope scope : scopes) {
            if (scope.kind == kind && scope.pattern.equals(pattern)) existing = scope;
        }
        Button include = Widgets.button("Include", "scope-btn include");
        Button exclude = Widgets.button("Exclude", "scope-btn exclude");
        include.getStyleClass().add(existing != null && existing.mode == ScopeMode.include ? "active" : "");
        exclude.getStyleClass().add(existing != null && existing.mode == ScopeMode.exclude ? "active" : "");
        String modulePath = activeModulePath();
        include.setOnAction(event -> {
            updateGroup(g -> setGroupScope(g, modulePath, ScopeMode.include, kind, pattern));
            refreshScopeTree();
            refreshRules();
        });
        exclude.setOnAction(event -> {
            updateGroup(g -> setGroupScope(g, modulePath, ScopeMode.exclude, kind, pattern));
            refreshScopeTree();
            refreshRules();
        });
        HBox buttons = new HBox(0);
        buttons.getStyleClass().add("scope-buttons");
        buttons.getChildren().addAll(include, exclude);
        return buttons;
    }

    private void setGroupScope(CoverageStatisticsGroup group, String modulePath,
                               ScopeMode mode, ScopeKind kind, String pattern) {
        ScopeMode existingMode = null;
        for (CoverageScope scope : group.scopes) {
            if (scope.modulePath.equals(modulePath) && scope.kind == kind && scope.pattern.equals(pattern)) {
                existingMode = scope.mode;
            }
        }
        group.scopes.removeIf(scope -> scope.modulePath.equals(modulePath) && scope.kind == kind && scope.pattern.equals(pattern));
        if (existingMode != mode) {
            group.scopes.add(new CoverageScope(modulePath, mode, kind, pattern));
        }
    }

    private void refreshRules() {
        rulesBox.getChildren().clear();
        List<CoverageScope> scopes = activeModuleScopes();
        if (scopes.isEmpty()) {
            Label empty = Widgets.label("尚未配置，默认统计模块全部业务类", "form-hint");
            rulesBox.getChildren().add(empty);
        }
        for (CoverageScope scope : scopes) {
            HBox row = new HBox(8);
            row.getStyleClass().addAll("rule", scope.mode == ScopeMode.include ? "include" : "exclude");
            row.setAlignment(Pos.CENTER_LEFT);
            Label sign = Widgets.label(scope.mode == ScopeMode.include ? "+" : "−", "rule-sign");
            VBox text = new VBox(0);
            Label pattern = Widgets.label(scope.pattern, "rule-pattern");
            pattern.getStyleClass().add("rule-pattern");
            Label kind = Widgets.label(scope.kind == ScopeKind.package_ ? "包及其子包" : "单个类", "rule-kind");
            kind.getStyleClass().add("rule-kind");
            text.getChildren().addAll(pattern, kind);
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            Button remove = Widgets.button("×", "rule-remove");
            remove.getStyleClass().add("rule-remove");
            String modulePath = activeModulePath();
            remove.setOnAction(event -> {
                updateGroup(g -> setGroupScope(g, modulePath, scope.mode, scope.kind, scope.pattern));
                refreshScopeTree();
                refreshRules();
            });
            row.getChildren().addAll(sign, text, spacer, remove);
            rulesBox.getChildren().add(row);
        }
    }

    // ---------- 统计结果 ----------

    private void refreshSummary() {
        summaryBox.getChildren().clear();
        CoverageStatisticsSummary summary = context.statisticsSummary.get();
        CoverageStatisticsSnapshotInfo snapshot = context.statisticsSnapshot.get();
        if (summary == null) {
            summaryHead.setText("统计结果");
            Label empty = Widgets.label(snapshot == null
                    ? "尚未生成统计结果；请先确认分组并运行选定模块 Test"
                    : "请确认分组后重新统计", "empty-state");
            summaryBox.getChildren().add(empty);
            return;
        }
        summaryHead.setText("统计结果 · " + summary.confirmedGroupCount + " 个已确认分组 · "
                + summary.classCount + " 类 · " + Text.coverageText(summary.lineCoverage));
        Label meta = Widgets.label("覆盖行 " + summary.coveredLines + " / " + (summary.coveredLines + summary.missedLines)
                + " · 快照 " + summary.snapshotId, "form-hint");
        summaryBox.getChildren().add(meta);
        for (CoverageStatisticsGroupResult group : summary.groups) {
            summaryBox.getChildren().add(renderGroupSummary(group));
        }
    }

    private Node renderGroupSummary(CoverageStatisticsGroupResult group) {
        VBox box = new VBox(4);
        HBox head = new HBox(8);
        head.setAlignment(Pos.CENTER_LEFT);
        Label name = Widgets.label(group.groupName, "stat-name bold");
        name.getStyleClass().addAll("stat-name", "bold");
        Label meta = Widgets.label(group.moduleCount + " 模块 · " + group.classCount + " 类 · "
                + Text.coverageText(group.lineCoverage), "stat-meta");
        meta.getStyleClass().add("stat-meta");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label value = Widgets.label(Text.coverageText(group.lineCoverage), "stat-value");
        value.getStyleClass().add("stat-value");
        head.getChildren().addAll(name, meta, spacer, value);
        box.getChildren().add(head);
        for (CoverageStatisticsModuleResult module : group.modules) {
            box.getChildren().add(renderModuleSummary(module, 1));
        }
        return box;
    }

    private Node renderModuleSummary(CoverageStatisticsModuleResult module, int depth) {
        VBox box = new VBox(2);
        HBox head = new HBox(6);
        head.setAlignment(Pos.CENTER_LEFT);
        head.setPadding(new javafx.geometry.Insets(3, 6, 3, 12 * depth));
        Label symbol = Widgets.label("J", "tree-symbol");
        Label name = Widgets.label(module.modulePath, "stat-name");
        name.getStyleClass().add("stat-name");
        Label meta = Widgets.label(module.classCount + " 类 · "
                + ("jacoco".equals(module.source) ? "JaCoCo" : "源码 0% 兜底"), "stat-meta");
        meta.getStyleClass().add("stat-meta");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label value = Widgets.label(Text.coverageText(module.lineCoverage), "stat-value");
        value.getStyleClass().add("stat-value");
        head.getChildren().addAll(symbol, name, meta, spacer, value);
        box.getChildren().add(head);
        for (CoverageStatisticsPackageResult packageItem : module.packages) {
            box.getChildren().add(renderPackageSummary(packageItem, depth + 1));
        }
        return box;
    }

    private Node renderPackageSummary(CoverageStatisticsPackageResult packageItem, int depth) {
        VBox box = new VBox(2);
        HBox head = new HBox(6);
        head.setAlignment(Pos.CENTER_LEFT);
        head.setPadding(new javafx.geometry.Insets(3, 6, 3, 12 * depth));
        Label symbol = Widgets.label("P", "tree-symbol");
        Label name = Widgets.label(packageItem.packageName, "stat-name");
        name.getStyleClass().add("stat-name");
        Label meta = Widgets.label(packageItem.classCount + " 类", "stat-meta");
        meta.getStyleClass().add("stat-meta");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label value = Widgets.label(Text.coverageText(packageItem.lineCoverage), "stat-value");
        value.getStyleClass().add("stat-value");
        head.getChildren().addAll(symbol, name, meta, spacer, value);
        box.getChildren().add(head);
        for (CoverageClassResult item : packageItem.classes) {
            HBox classRow = new HBox(6);
            classRow.setAlignment(Pos.CENTER_LEFT);
            classRow.setPadding(new javafx.geometry.Insets(2, 6, 2, 12 * (depth + 1)));
            Label connector = Widgets.label("└", "tree-connector");
            connector.setStyle("-fx-text-fill: #c3c8d4;");
            Label className = Widgets.label(item.className, "stat-meta");
            className.getStyleClass().add("stat-meta");
            Region classSpacer = new Region();
            HBox.setHgrow(classSpacer, Priority.ALWAYS);
            Label classValue = Widgets.label(Text.coverageText(item.lineCoverage), "stat-meta");
            classValue.getStyleClass().add("stat-meta");
            classRow.getChildren().addAll(connector, className, classSpacer, classValue);
            box.getChildren().add(classRow);
        }
        return box;
    }
}
