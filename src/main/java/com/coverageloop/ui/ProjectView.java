package com.coverageloop.ui;

import com.coverageloop.model.ProjectScanResult;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/** 视图 01：项目初始化 */
public class ProjectView {

    private final AppContext context;

    public ProjectView(AppContext context) {
        this.context = context;
    }

    public Node build() {
        VBox stack = new VBox(14);
        stack.getStyleClass().add("stack");
        stack.setMaxWidth(900);

        // Hero 卡
        VBox hero = new VBox(6);
        hero.getStyleClass().add("panel");
        Label tag = Widgets.label("STEP 01", "tag");
        Label title = Widgets.label("从一个根 POM 开始", "panel-title");
        title.setStyle("-fx-font-size: 20px;");
        Label copy = Widgets.label("软件会递归解析 Maven Reactor，识别模块、源码目录和测试目录，不修改任何原始 POM。", "panel-subtitle");
        hero.getChildren().addAll(tag, title, copy);
        stack.getChildren().add(hero);

        // 项目入口
        VBox panel = new VBox(12);
        panel.getStyleClass().add("panel");
        VBox heading = new VBox(2);
        Label panelTitle = Widgets.label("项目入口", "panel-title");
        Label panelSubtitle = Widgets.label("选择聚合项目根目录中的 pom.xml", "panel-subtitle");
        heading.getChildren().addAll(panelTitle, panelSubtitle);
        panel.getChildren().add(heading);

        Label fieldLabel = Widgets.label("根 POM 路径", "form-label");
        panel.getChildren().add(fieldLabel);

        TextField pathField = new TextField();
        pathField.setPromptText("D:\\Project\\hisun_backend\\pom.xml");
        pathField.textProperty().bindBidirectional(context.rootPomPath);
        Button browse = Widgets.button("浏览…", "button secondary");
        browse.setOnAction(event -> context.chooseRootPom());
        HBox pathPicker = new HBox(8);
        pathPicker.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(pathField, Priority.ALWAYS);
        pathPicker.getChildren().addAll(pathField, browse);
        panel.getChildren().add(pathPicker);

        HBox hints = new HBox();
        hints.setAlignment(Pos.CENTER_LEFT);
        Label hint1 = Widgets.label("初始化时将检查模块 POM 与源码目录", "form-hint");
        Label hint2 = Widgets.label("支持在 .coverage-loop/configs 下保存多套配置", "form-hint");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        hints.getChildren().addAll(hint1, spacer, hint2);
        panel.getChildren().add(hints);

        HBox actions = new HBox(10);
        actions.setAlignment(Pos.CENTER_RIGHT);
        Label scanned = Widgets.label("", "success-copy");
        scanned.setStyle("-fx-text-fill: #15803d; -fx-font-size: 12px;");
        Button initButton = Widgets.button("初始化项目", "button primary");
        initButton.setOnAction(event -> context.initialize());
        context.busy.addListener((observable, oldValue, newValue) -> {
            initButton.setDisable(newValue);
            initButton.setText(newValue ? "正在扫描…" : (context.project.get() != null ? "重新初始化" : "初始化项目"));
        });
        context.project.addListener((observable, oldValue, newValue) -> {
            initButton.setText(newValue != null ? "重新初始化" : "初始化项目");
            scanned.setText(newValue != null ? "已扫描 " + newValue.modules.size() + " 个模块" : "");
        });
        actions.getChildren().addAll(scanned, initButton);
        panel.getChildren().add(actions);
        stack.getChildren().add(panel);

        return stack;
    }
}
