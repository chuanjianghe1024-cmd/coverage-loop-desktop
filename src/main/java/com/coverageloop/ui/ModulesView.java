package com.coverageloop.ui;

import com.coverageloop.model.MavenModule;
import com.coverageloop.model.ProjectConfig;
import com.coverageloop.model.ProjectScanResult;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;

/** 视图 02：模块选择 */
public class ModulesView {

    private final AppContext context;

    public ModulesView(AppContext context) {
        this.context = context;
    }

    public Node build() {
        VBox stack = new VBox(14);
        stack.setMaxWidth(1000);

        HBox metrics = new HBox(12);
        metrics.setAlignment(Pos.CENTER_LEFT);
        Label totalModules = Widgets.label("0", "metric-value");
        Label selectedModules = Widgets.label("0", "metric-value");
        Label testModules = Widgets.label("0", "metric-value");
        VBox m1 = metricBox("发现模块", totalModules, "递归 Reactor");
        VBox m2 = metricBox("已选择", selectedModules, "参与本轮测试");
        m2.getStyleClass().add("accent");
        VBox m3 = metricBox("含测试目录", testModules, "src/test/java");
        metrics.getChildren().addAll(m1, m2, m3);
        stack.getChildren().add(metrics);

        VBox panel = new VBox(12);
        panel.getStyleClass().add("panel");

        TextField searchField = new TextField();
        searchField.setPromptText("搜索模块或路径");
        searchField.setPrefWidth(240);
        HBox heading = Widgets.heading("选择目标模块", "依赖模块会由 Maven 的 -am 自动补齐", searchField);
        panel.getChildren().add(heading);

        VBox moduleList = new VBox(6);
        moduleList.setMaxHeight(460);
        javafx.scene.control.ScrollPane listScroll = new javafx.scene.control.ScrollPane(moduleList);
        listScroll.setFitToWidth(true);
        listScroll.setPrefHeight(430);
        panel.getChildren().add(listScroll);

        Label warningsLabel = new Label();
        warningsLabel.setWrapText(true);
        warningsLabel.setVisible(false);
        warningsLabel.setManaged(false);
        panel.getChildren().add(warningsLabel);

        HBox actions = new HBox(10);
        actions.setAlignment(Pos.CENTER_RIGHT);
        Label selectedCount = Widgets.label("已选择 0 个模块", "form-hint");
        Button nextButton = Widgets.button("下一步：配置范围", "button primary");
        nextButton.setOnAction(event -> context.view.set(AppContext.View.SCOPE));
        actions.getChildren().addAll(selectedCount, nextButton);
        panel.getChildren().add(actions);

        context.project.addListener((observable, oldValue, newValue) -> refresh(newValue, searchField.getText(),
                moduleList, totalModules, selectedModules, testModules, selectedCount, warningsLabel));
        context.config.addListener((observable, oldValue, newValue) -> refresh(context.project.get(), searchField.getText(),
                moduleList, totalModules, selectedModules, testModules, selectedCount, warningsLabel));
        searchField.textProperty().addListener((observable, oldValue, newValue) -> refresh(context.project.get(), newValue,
                moduleList, totalModules, selectedModules, testModules, selectedCount, warningsLabel));
        refresh(context.project.get(), "", moduleList, totalModules, selectedModules, testModules, selectedCount, warningsLabel);
        return stack;
    }

    private VBox metricBox(String labelText, Label value, String subText) {
        VBox box = new VBox(0);
        box.getStyleClass().add("metric");
        Label label = Widgets.label(labelText, "metric-label");
        Label sub = Widgets.label(subText, "metric-sub");
        box.getChildren().addAll(label, value, sub);
        return box;
    }

    private void refresh(ProjectScanResult project, String keyword, VBox moduleList,
                         Label totalModules, Label selectedModules, Label testModules,
                         Label selectedCount, Label warningsLabel) {
        moduleList.getChildren().clear();
        if (project == null) return;
        ProjectConfig config = context.config.get();
        List<String> selected = config == null ? List.of() : config.selectedModulePaths;
        List<MavenModule> visible = new ArrayList<>();
        for (MavenModule module : project.modules) {
            String haystack = (module.artifactId + " " + module.relativePath).toLowerCase();
            if (keyword == null || keyword.isBlank() || haystack.contains(keyword.toLowerCase())) {
                visible.add(module);
            }
        }
        totalModules.setText(String.valueOf(project.modules.size()));
        selectedModules.setText(String.valueOf(selected.size()));
        int testCount = 0;
        for (MavenModule module : project.modules) {
            if (module.hasTests) testCount += 1;
        }
        testModules.setText(String.valueOf(testCount));
        selectedCount.setText("已选择 " + selected.size() + " 个模块");

        for (MavenModule module : visible) {
            moduleList.getChildren().add(moduleRow(module, selected.contains(module.relativePath)));
        }
        if (project.warnings.isEmpty()) {
            warningsLabel.setVisible(false);
            warningsLabel.setManaged(false);
        } else {
            warningsLabel.getStyleClass().setAll("warning-box");
            warningsLabel.setText(String.join("；", project.warnings));
            warningsLabel.setVisible(true);
            warningsLabel.setManaged(true);
        }
    }

    private Node moduleRow(MavenModule module, boolean checked) {
        HBox row = new HBox(10);
        row.getStyleClass().addAll("module-row", checked ? "selected" : "");
        row.setAlignment(Pos.CENTER_LEFT);
        CheckBox checkBox = new CheckBox();
        checkBox.setSelected(checked);
        checkBox.setDisable(!module.hasMainSources);
        checkBox.setOnAction(event -> context.toggleModule(module.relativePath));
        Label icon = Widgets.label("pom".equals(module.packaging) ? "P" : "J", "module-icon");
        VBox main = new VBox(0);
        Label name = Widgets.label(module.artifactId, "module-name");
        name.getStyleClass().add("module-name");
        Label path = Widgets.label(module.relativePath, "module-path");
        path.getStyleClass().add("module-path");
        main.getChildren().addAll(name, path);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label sourcePill = Widgets.pill(module.hasMainSources ? "有源码" : "无主源码", module.hasMainSources ? "green" : "gray");
        Label testPill = Widgets.pill(module.hasTests ? "有测试" : "待补测试", module.hasTests ? "blue" : "gray");
        row.getChildren().addAll(checkBox, icon, main, spacer, sourcePill, testPill);
        return row;
    }
}
