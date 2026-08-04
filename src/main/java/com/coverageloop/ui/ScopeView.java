package com.coverageloop.ui;

import com.coverageloop.model.CoverageScope;
import com.coverageloop.model.JavaClassInfo;
import com.coverageloop.model.MavenModule;
import com.coverageloop.model.ModuleSources;
import com.coverageloop.model.ProjectConfig;
import com.coverageloop.model.ScopeKind;
import com.coverageloop.model.ScopeMode;
import com.coverageloop.service.ScopeSelection;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 视图 03：包与类 Include / Exclude 配置 */
public class ScopeView {

    private final AppContext context;
    private final Set<String> expanded = new HashSet<>();
    private final ComboBox<String> moduleSelect = new ComboBox<>();
    private final TextField searchField = new TextField();
    private final VBox treeBox = new VBox(2);
    private final VBox rulesBox = new VBox(6);
    private final Label sourceStatsLabel = new Label();
    private final Label treeSummary = new Label();

    public ScopeView(AppContext context) {
        this.context = context;
    }

    public Node build() {
        HBox layout = new HBox(14);
        layout.setPrefWidth(1200);

        // 左侧：代码范围
        VBox explorer = new VBox(12);
        explorer.getStyleClass().add("panel");
        explorer.setMaxWidth(820);
        HBox.setHgrow(explorer, Priority.ALWAYS);
        explorer.getChildren().add(Widgets.heading("代码范围", "Exclude 优先于 Include；不选 Include 表示包含全部"));

        moduleSelect.setPrefWidth(320);
        searchField.setPromptText("搜索包或类");
        searchField.setPrefWidth(240);
        HBox controls = new HBox(10);
        controls.setAlignment(Pos.CENTER_LEFT);
        controls.getChildren().addAll(moduleSelect, searchField);
        explorer.getChildren().add(controls);

        treeSummary.getStyleClass().add("form-hint");
        explorer.getChildren().add(treeSummary);

        ScrollPane treeScroll = new ScrollPane(treeBox);
        treeScroll.setFitToWidth(true);
        treeScroll.setPrefHeight(560);
        explorer.getChildren().add(treeScroll);
        treeBox.setMaxWidth(780);

        // 右侧：当前规则
        VBox side = new VBox(12);
        side.setPrefWidth(330);
        side.getStyleClass().add("panel");
        side.getChildren().add(Widgets.heading("当前规则", context.activeModulePath.get() == null ? "未选择模块" : context.activeModulePath.get()));
        side.getChildren().add(rulesBox);
        sourceStatsLabel.getStyleClass().add("form-hint");
        side.getChildren().add(sourceStatsLabel);
        Button nextButton = Widgets.button("下一步：运行配置", "button primary");
        nextButton.setMaxWidth(Double.MAX_VALUE);
        nextButton.setOnAction(event -> context.view.set(AppContext.View.RUN));
        side.getChildren().add(nextButton);

        // 事件
        moduleSelect.valueProperty().addListener((observable, oldValue, newValue) -> {
            if (newValue != null) context.activeModulePath.set(newValue);
        });
        searchField.textProperty().addListener((observable, oldValue, newValue) -> rebuildTree());
        context.activeModulePath.addListener((observable, oldValue, newValue) -> refreshModuleSelect());
        context.config.addListener((observable, oldValue, newValue) -> {
            refreshModuleSelect();
            rebuildTree();
            refreshRules();
        });
        context.activeSources.addListener((observable, oldValue, newValue) -> {
            rebuildTree();
            refreshRules();
        });

        refreshModuleSelect();
        MavenModule activeModule = activeModule();
        if (activeModule != null && context.activeSources.get() == null) {
            context.loadModuleSources(activeModule);
        }
        rebuildTree();
        refreshRules();
        layout.getChildren().addAll(explorer, side);
        return layout;
    }

    private MavenModule activeModule() {
        ProjectConfig config = context.config.get();
        if (config == null) return null;
        String path = context.activeModulePath.get();
        for (MavenModule module : context.project.get().modules) {
            if (module.relativePath.equals(path)) return module;
        }
        return null;
    }

    private void refreshModuleSelect() {
        ProjectConfig config = context.config.get();
        if (config == null) return;
        String previous = moduleSelect.getValue();
        moduleSelect.getItems().clear();
        for (String modulePath : config.selectedModulePaths) {
            for (MavenModule module : context.project.get().modules) {
                if (module.relativePath.equals(modulePath)) {
                    moduleSelect.getItems().add(module.label());
                }
            }
        }
        String current = context.activeModulePath.get();
        moduleSelect.setValue(findLabel(current));
    }

    private String findLabel(String modulePath) {
        if (modulePath == null) return null;
        for (MavenModule module : context.project.get().modules) {
            if (module.relativePath.equals(modulePath)) return module.label();
        }
        return null;
    }

    private static class TreeNode {
        String name;
        String qualifiedName;
        List<TreeNode> children = new ArrayList<>();
        List<JavaClassInfo> classes = new ArrayList<>();
    }

    private TreeNode buildPackageTree(List<JavaClassInfo> classes) {
        TreeNode root = new TreeNode();
        for (JavaClassInfo item : classes) {
            TreeNode current = root;
            StringBuilder qualified = new StringBuilder();
            for (String segment : item.packageName.split("\\.")) {
                if (segment.isEmpty()) continue;
                if (!qualified.isEmpty()) qualified.append('.');
                qualified.append(segment);
                TreeNode child = null;
                for (TreeNode node : current.children) {
                    if (node.name.equals(segment)) {
                        child = node;
                        break;
                    }
                }
                if (child == null) {
                    child = new TreeNode();
                    child.name = segment;
                    child.qualifiedName = qualified.toString();
                    current.children.add(child);
                }
                current = child;
            }
            current.classes.add(item);
        }
        root.children.sort((a, b) -> a.name.compareTo(b.name));
        for (TreeNode node : root.children) sortTree(node);
        return root;
    }

    private void sortTree(TreeNode node) {
        node.children.sort((a, b) -> a.name.compareTo(b.name));
        node.classes.sort((a, b) -> a.name.compareTo(b.name));
        for (TreeNode child : node.children) sortTree(child);
    }

    private boolean nodeMatches(TreeNode node, String keyword) {
        if (keyword.isEmpty()) return true;
        if (node.qualifiedName.toLowerCase().contains(keyword)) return true;
        for (JavaClassInfo item : node.classes) {
            if (item.qualifiedName.toLowerCase().contains(keyword)) return true;
        }
        for (TreeNode child : node.children) {
            if (nodeMatches(child, keyword)) return true;
        }
        return false;
    }

    private void rebuildTree() {
        treeBox.getChildren().clear();
        ModuleSources sources = context.activeSources.get();
        String keyword = searchField.getText().trim().toLowerCase();
        if (sources == null) {
            Label empty = Widgets.label("正在扫描 Java 源码…", "empty-state");
            treeBox.getChildren().add(empty);
            treeSummary.setText("");
            return;
        }
        // 自动展开深度 ≤ 2 的包
        for (String packageName : sources.packages) {
            if (packageName.split("\\.").length <= 2) expanded.add(packageName);
        }
        treeSummary.setText(sources.packages.size() + " 包 · " + sources.classes.size() + " 类");
        TreeNode tree = buildPackageTree(sources.classes);
        boolean searching = !keyword.isEmpty();
        for (TreeNode node : tree.children) {
            renderPackageNode(node, 0, searching, keyword);
        }
        // 默认包类
        for (JavaClassInfo item : tree.classes) {
            renderClassRow(item, 46, "默认包");
        }
    }

    private void renderPackageNode(TreeNode node, int depth, boolean searching, String keyword) {
        if (!nodeMatches(node, keyword)) return;
        boolean open = searching || expanded.contains(node.qualifiedName);
        boolean packageMatches = searching && node.qualifiedName.toLowerCase().contains(keyword);
        HBox row = new HBox(6);
        row.getStyleClass().add("tree-row package-row");
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new javafx.geometry.Insets(4, 8, 4, 10 + depth * 16));
        Button expander = new Button(open ? "⌄" : "›");
        expander.getStyleClass().add("tree-expander");
        expander.setOnAction(event -> {
            if (expanded.contains(node.qualifiedName)) expanded.remove(node.qualifiedName);
            else expanded.add(node.qualifiedName);
            rebuildTree();
        });
        Label symbol = Widgets.label("P", "tree-symbol");
        VBox nameBox = new VBox(0);
        Label name = Widgets.label(node.name, "tree-name-main");
        name.getStyleClass().add("tree-name-main");
        Label qualified = Widgets.label(node.qualifiedName, "tree-name-sub");
        qualified.getStyleClass().add("tree-name-sub");
        nameBox.getChildren().addAll(name, qualified);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox scopeButtons = scopeButtons(ScopeKind.package_, node.qualifiedName);
        row.getChildren().addAll(expander, symbol, nameBox, spacer, scopeButtons);
        treeBox.getChildren().add(row);

        if (open) {
            for (TreeNode child : node.children) {
                renderPackageNode(child, depth + 1, searching, keyword);
            }
            for (JavaClassInfo item : node.classes) {
                if (!searching || packageMatches || item.qualifiedName.toLowerCase().contains(keyword)) {
                    renderClassRow(item, 46 + depth * 16, node.qualifiedName);
                }
            }
        }
    }

    private void renderClassRow(JavaClassInfo item, int indent, String packageName) {
        HBox row = new HBox(6);
        row.getStyleClass().add("tree-row class-row");
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new javafx.geometry.Insets(4, 8, 4, indent));
        Label connector = Widgets.label("└", "tree-connector");
        connector.setStyle("-fx-text-fill: #c3c8d4;");
        Label symbol = Widgets.label("C", "tree-symbol clazz");
        VBox nameBox = new VBox(0);
        Label name = Widgets.label(item.name, "tree-name-main");
        name.getStyleClass().add("tree-name-main");
        Label qualified = Widgets.label(item.qualifiedName, "tree-name-sub");
        qualified.getStyleClass().add("tree-name-sub");
        nameBox.getChildren().addAll(name, qualified);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox scopeButtons = scopeButtons(ScopeKind.class_, item.qualifiedName);
        row.getChildren().addAll(connector, symbol, nameBox, spacer, scopeButtons);
        treeBox.getChildren().add(row);
    }

    private HBox scopeButtons(ScopeKind kind, String pattern) {
        CoverageScope scope = context.scopeFor(kind, pattern);
        Button include = Widgets.button("Include", "scope-btn include");
        Button exclude = Widgets.button("Exclude", "scope-btn exclude");
        include.getStyleClass().add(scope != null && scope.mode == ScopeMode.include ? "active" : "");
        exclude.getStyleClass().add(scope != null && scope.mode == ScopeMode.exclude ? "active" : "");
        include.setOnAction(event -> {
            context.setScope(ScopeMode.include, kind, pattern);
            rebuildTree();
            refreshRules();
        });
        exclude.setOnAction(event -> {
            context.setScope(ScopeMode.exclude, kind, pattern);
            rebuildTree();
            refreshRules();
        });
        HBox buttons = new HBox(0);
        buttons.getStyleClass().add("scope-buttons");
        buttons.getChildren().addAll(include, exclude);
        return buttons;
    }

    private void refreshRules() {
        rulesBox.getChildren().clear();
        String modulePath = context.activeModulePath.get();
        ProjectConfig config = context.config.get();
        List<CoverageScope> scopes = new ArrayList<>();
        if (config != null && modulePath != null) {
            for (CoverageScope scope : config.scopes) {
                if (scope.modulePath.equals(modulePath)) scopes.add(scope);
            }
        }
        if (scopes.isEmpty()) {
            Label empty = Widgets.label("尚未配置，默认统计模块全部业务类", "form-hint");
            rulesBox.getChildren().add(empty);
        } else {
            for (CoverageScope scope : scopes) {
                rulesBox.getChildren().add(ruleRow(scope));
            }
        }
        // 选中文件统计
        ModuleSources sources = context.activeSources.get();
        if (sources != null) {
            int[] counts = ScopeSelection.countSelectedFiles(scopes, sources.classes, sources.testClasses);
            sourceStatsLabel.setText("选中 " + counts[0] + " 个业务文件 / " + sources.sourceFileCount
                    + "，测试文件 " + counts[1] + " / " + sources.testFileCount);
        } else {
            sourceStatsLabel.setText("");
        }
    }

    private Node ruleRow(CoverageScope scope) {
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
        remove.setOnAction(event -> {
            context.setScope(scope.mode, scope.kind, scope.pattern);
            rebuildTree();
            refreshRules();
        });
        row.getChildren().addAll(sign, text, spacer, remove);
        return row;
    }
}
