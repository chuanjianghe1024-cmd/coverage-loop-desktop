package com.coverageloop.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/** 界面组件工厂 */
final class Widgets {

    private Widgets() {
    }

    static Button button(String text, String styleClass) {
        Button button = new Button(text);
        button.getStyleClass().addAll(styleClass.split(" "));
        return button;
    }

    static Button textButton(String text) {
        Button button = new Button(text);
        button.getStyleClass().add("text-button");
        return button;
    }

    static Label label(String text, String styleClass) {
        Label label = new Label(text);
        if (styleClass != null) label.getStyleClass().addAll(styleClass.split(" "));
        return label;
    }

    static Label pill(String text, String styleClass) {
        Label pill = new Label(text);
        pill.getStyleClass().addAll("pill", styleClass);
        return pill;
    }

    /** 面板：标题 + 副标题 + 内容 */
    static VBox panel(String title, String subtitle, Node content) {
        VBox panel = new VBox(12);
        panel.getStyleClass().add("panel");
        if (title != null) {
            VBox heading = new VBox(2);
            Label titleLabel = label(title, "panel-title");
            if (subtitle != null) {
                Label subtitleLabel = label(subtitle, "panel-subtitle");
                heading.getChildren().addAll(titleLabel, subtitleLabel);
            } else {
                heading.getChildren().add(titleLabel);
            }
            panel.getChildren().add(heading);
        }
        if (content != null) panel.getChildren().add(content);
        return panel;
    }

    /** 面板标题行（左侧标题 + 右侧操作） */
    static HBox heading(String title, String subtitle, Node... actions) {
        HBox row = new HBox();
        row.setAlignment(Pos.CENTER_LEFT);
        row.setSpacing(10);
        VBox text = new VBox(2);
        Label titleLabel = label(title, "panel-title");
        text.getChildren().add(titleLabel);
        if (subtitle != null) {
            Label subtitleLabel = label(subtitle, "panel-subtitle");
            text.getChildren().add(subtitleLabel);
        }
        row.getChildren().add(text);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        row.getChildren().add(spacer);
        for (Node action : actions) row.getChildren().add(action);
        return row;
    }

    /** 指标卡 */
    static VBox metric(String labelText, String valueText, String subText, boolean accent) {
        VBox metric = new VBox(0);
        metric.getStyleClass().addAll("metric", accent ? "accent" : "");
        Label label = new Label(labelText);
        label.getStyleClass().add("metric-label");
        Label value = new Label(valueText);
        value.getStyleClass().add("metric-value");
        Label sub = new Label(subText);
        sub.getStyleClass().add("metric-sub");
        metric.getChildren().addAll(label, value, sub);
        return metric;
    }

    /** 表单标签 + 控件 */
    static VBox field(String labelText, Node control, String hint) {
        VBox box = new VBox(4);
        Label label = new Label(labelText);
        label.getStyleClass().add("form-label");
        box.getChildren().add(label);
        box.getChildren().add(control);
        if (hint != null) {
            Label hintLabel = new Label(hint);
            hintLabel.getStyleClass().add("form-hint");
            box.getChildren().add(hintLabel);
        }
        return box;
    }

    static Region hgap(double width) {
        Region spacer = new Region();
        spacer.setMinWidth(width);
        spacer.setPrefWidth(width);
        return spacer;
    }

    static HBox row(Node... nodes) {
        HBox row = new HBox(10);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getChildren().addAll(nodes);
        return row;
    }
}
