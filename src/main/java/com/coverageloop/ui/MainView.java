package com.coverageloop.ui;

import com.coverageloop.model.ProjectScanResult;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/** 应用外壳：侧边栏导航 + 顶栏 + Toast + 内容区 */
public class MainView {

    private final AppContext context;
    private final StackPane contentArea = new StackPane();
    private final ComboBox<String> configSelect = new ComboBox<>();
    private final TextField configName = new TextField();
    private final Label toastLabel = new Label();
    private final HBox toast = new HBox();
    private final Label navStatusTitle = new Label("尚未初始化");
    private final Label navStatusSubtitle = new Label("请选择根 POM");
    private final Label topbarTitle = new Label("项目初始化");
    private final HBox configActions = new HBox();
    private boolean syncingConfigUi = false;

    public MainView(AppContext context) {
        this.context = context;
    }

    public Parent build() {
        BorderPane shell = new BorderPane();
        shell.getStyleClass().add("app-shell");
        shell.setLeft(buildSidebar());
        shell.setCenter(buildMainArea());
        return shell;
    }

    private Node buildSidebar() {
        VBox sidebar = new VBox();
        sidebar.getStyleClass().add("sidebar");
        sidebar.setPrefWidth(232);

        HBox brand = new HBox(10);
        brand.setAlignment(Pos.CENTER_LEFT);
        StackPane mark = new StackPane();
        mark.getStyleClass().add("brand-mark");
        Region dot = new Region();
        dot.getStyleClass().add("dot");
        mark.getChildren().add(dot);
        VBox brandText = new VBox(0);
        Label title = new Label("Coverage Loop");
        title.getStyleClass().add("brand-title");
        Label subtitle = new Label("自动补测工作台");
        subtitle.getStyleClass().add("brand-subtitle");
        brandText.getChildren().addAll(title, subtitle);
        brand.getChildren().addAll(mark, brandText);
        sidebar.getChildren().add(brand);

        Label caption = new Label("配置流程");
        caption.getStyleClass().add("step-caption");
        sidebar.getChildren().add(caption);

        for (AppContext.View item : new AppContext.View[]{
                AppContext.View.PROJECT, AppContext.View.MODULES,
                AppContext.View.SCOPE, AppContext.View.RUN}) {
            sidebar.getChildren().add(navButton(item));
        }

        Label analysisCaption = new Label("分析工具");
        analysisCaption.getStyleClass().add("step-caption");
        sidebar.getChildren().add(analysisCaption);
        sidebar.getChildren().add(navButton(AppContext.View.STATISTICS));

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);
        sidebar.getChildren().add(spacer);

        HBox status = new HBox(10);
        status.getStyleClass().add("sidebar-status");
        status.setAlignment(Pos.CENTER_LEFT);
        Region statusDot = new Region();
        statusDot.getStyleClass().add("status-dot");
        context.project.addListener((observable, oldValue, newValue) ->
                statusDot.getStyleClass().setAll("status-dot", newValue != null ? "online" : ""));
        VBox statusText = new VBox(0);
        navStatusTitle.getStyleClass().add("nav-title");
        navStatusSubtitle.getStyleClass().add("nav-subtitle");
        statusText.getChildren().addAll(navStatusTitle, navStatusSubtitle);
        status.getChildren().addAll(statusDot, statusText);
        sidebar.getChildren().add(status);

        Label footer = new Label("JavaFX · 0.5.2");
        footer.getStyleClass().add("sidebar-footer");
        sidebar.getChildren().add(footer);
        return sidebar;
    }

    private Button navButton(AppContext.View item) {
        Button button = new Button();
        button.getStyleClass().add("nav-button");
        button.setMaxWidth(Double.MAX_VALUE);
        HBox content = new HBox(8);
        content.setAlignment(Pos.CENTER_LEFT);
        Label number = new Label(item.number);
        number.getStyleClass().add("nav-number");
        VBox text = new VBox(0);
        Label title = new Label(item.title);
        title.getStyleClass().add("nav-title");
        Label subtitle = new Label(item.subtitle);
        subtitle.getStyleClass().add("nav-subtitle");
        text.getChildren().addAll(title, subtitle);
        content.getChildren().addAll(number, text);
        button.setGraphic(content);
        button.setDisable(item != AppContext.View.PROJECT);
        button.setOnAction(event -> navigate(item));

        ChangeListener<AppContext.View> viewListener = (observable, oldValue, newValue) -> {
            button.setDisable(item != AppContext.View.PROJECT && context.project.get() == null);
            if (newValue == item) button.getStyleClass().setAll("nav-button", "active");
            else button.getStyleClass().setAll("nav-button");
        };
        context.view.addListener(viewListener);
        context.project.addListener((observable, oldValue, newValue) -> {
            boolean enabled = item == AppContext.View.PROJECT || newValue != null;
            button.setDisable(!enabled || (context.running.get() && false));
        });
        return button;
    }

    private void navigate(AppContext.View target) {
        context.view.set(target);
    }

    private Node buildMainArea() {
        VBox main = new VBox();
        main.getChildren().add(buildTopbar());
        main.getChildren().add(buildToast());
        main.getChildren().add(buildContent());
        VBox.setVgrow(main.getChildren().get(2), Priority.ALWAYS);
        return main;
    }

    private Node buildTopbar() {
        HBox topbar = new HBox();
        topbar.getStyleClass().add("topbar");
        topbar.setAlignment(Pos.CENTER_LEFT);

        VBox heading = new VBox(0);
        Label eyebrow = new Label("MAVEN MULTI-MODULE");
        eyebrow.getStyleClass().add("eyebrow");
        topbarTitle.getStyleClass().add("topbar-title");
        heading.getChildren().addAll(eyebrow, topbarTitle);
        topbar.getChildren().add(heading);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        topbar.getChildren().add(spacer);

        configSelect.setPromptText("选择配置");
        configSelect.setPrefWidth(180);
        configSelect.getStyleClass().add("config-select");
        configSelect.valueProperty().addListener((observable, oldValue, newValue) -> {
            if (syncingConfigUi || newValue == null) return;
            if (context.config.get() == null || !newValue.equals(context.config.get().id)) {
                context.switchConfig(newValue);
            }
        });
        configName.setPromptText("配置名称");
        configName.setPrefWidth(160);
        configName.textProperty().addListener((observable, oldValue, newValue) -> {
            if (syncingConfigUi || context.config.get() == null) return;
            com.coverageloop.model.ProjectConfig current = context.config.get();
            if (newValue != null && !newValue.equals(current.name)) {
                com.coverageloop.model.ProjectConfig copy = current.copy();
                copy.name = newValue;
                context.config.set(copy);
            }
        });

        Button copyButton = button("新建副本", "button ghost");
        copyButton.setOnAction(event -> context.createConfigCopy());
        Button deleteButton = button("删除", "button ghost");
        deleteButton.setOnAction(event -> context.deleteCurrentConfig());
        Button saveButton = button("保存配置", "button ghost");
        saveButton.setOnAction(event -> context.save());

        configActions.setSpacing(8);
        configActions.setAlignment(Pos.CENTER_LEFT);
        configActions.getChildren().addAll(copyButton, deleteButton, saveButton);

        Label avatar = new Label("CL");
        avatar.getStyleClass().add("avatar");
        topbar.getChildren().addAll(configSelect, configName, configActions, avatar);

        // 同步配置 UI 与状态
        context.config.addListener((observable, oldValue, newValue) -> syncConfigUi());
        context.configs.addListener((javafx.collections.ListChangeListener<com.coverageloop.model.ConfigSummary>) change -> syncConfigUi());
        context.running.addListener((observable, oldValue, newValue) -> {
            configSelect.setDisable(newValue);
            configName.setDisable(newValue);
            copyButton.setDisable(newValue);
            deleteButton.setDisable(newValue);
            saveButton.setDisable(newValue);
        });
        context.busy.addListener((observable, oldValue, newValue) -> saveButton.setDisable(newValue));
        syncConfigUi();
        return topbar;
    }

    private void syncConfigUi() {
        if (context.config.get() == null) {
            configActions.setVisible(false);
            configActions.setManaged(false);
            configSelect.setVisible(false);
            configSelect.setManaged(false);
            configName.setVisible(false);
            configName.setManaged(false);
            return;
        }
        configActions.setVisible(true);
        configActions.setManaged(true);
        configSelect.setVisible(true);
        configSelect.setManaged(true);
        configName.setVisible(true);
        configName.setManaged(true);
        syncingConfigUi = true;
        String currentId = context.config.get().id;
        boolean hasCurrent = false;
        for (com.coverageloop.model.ConfigSummary summary : context.configs) {
            if (summary.id.equals(currentId)) hasCurrent = true;
        }
        if (!hasCurrent) {
            configSelect.getItems().clear();
            configSelect.getItems().add(currentId);
        } else {
            configSelect.getItems().clear();
            for (com.coverageloop.model.ConfigSummary summary : context.configs) {
                configSelect.getItems().add(summary.id);
            }
        }
        configSelect.setValue(currentId);
        configName.setText(context.config.get().name);
        syncingConfigUi = false;
    }

    private Node buildToast() {
        toast.setVisible(false);
        toast.setManaged(false);
        toast.getStyleClass().add("toast");
        toast.setPadding(new Insets(10, 18, 0, 18));
        Label icon = new Label("✓");
        icon.getStyleClass().add("toast-icon");
        Button close = new Button("×");
        close.getStyleClass().add("toast-close");
        close.setOnAction(event -> {
            context.toastMessage.set("");
        });
        context.toastMessage.addListener((observable, oldValue, newValue) -> {
            boolean visible = newValue != null && !newValue.isEmpty();
            toast.setVisible(visible);
            toast.setManaged(visible);
            toastLabel.setText(newValue == null ? "" : newValue);
            toastLabel.setWrapText(true);
            boolean error = context.toastError.get();
            icon.getStyleClass().setAll("toast-icon", error ? "error" : "success");
            icon.setText(error ? "!" : "✓");
        });
        context.toastError.addListener((observable, oldValue, newValue) -> {
            boolean error = newValue;
            icon.getStyleClass().setAll("toast-icon", error ? "error" : "success");
            icon.setText(error ? "!" : "✓");
            toast.getStyleClass().setAll("toast", error ? "error" : "");
        });
        toastLabel.getStyleClass().add("toast-message");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        toast.getChildren().addAll(icon, toastLabel, spacer, close);
        return toast;
    }

    private Node buildContent() {
        contentArea.setPadding(new Insets(18, 22, 22, 22));
        context.view.addListener((observable, oldValue, newValue) -> {
            topbarTitle.setText(newValue.title);
            contentArea.getChildren().setAll(createView(newValue));
        });
        context.project.addListener((observable, oldValue, newValue) -> {
            if (newValue != null) {
                navStatusTitle.setText(newValue.rootArtifactId);
                navStatusSubtitle.setText(newValue.modules.size() + " 个 Reactor 模块");
            } else {
                navStatusTitle.setText("尚未初始化");
                navStatusSubtitle.setText("请选择根 POM");
            }
        });
        contentArea.getChildren().setAll(createView(AppContext.View.PROJECT));
        return contentArea;
    }

    private Node createView(AppContext.View view) {
        Node node;
        switch (view) {
            case MODULES:
                node = new ModulesView(context).build();
                break;
            case SCOPE:
                node = new ScopeView(context).build();
                break;
            case RUN:
                node = new RunView(context).build();
                break;
            case STATISTICS:
                node = new StatisticsView(context).build();
                break;
            default:
                node = new ProjectView(context).build();
        }
        ScrollPane scrollPane = new ScrollPane(node);
        scrollPane.setFitToWidth(true);
        scrollPane.setFitToHeight(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        return scrollPane;
    }

    private static Button button(String text, String styleClass) {
        Button button = new Button(text);
        button.getStyleClass().addAll(styleClass.split(" "));
        return button;
    }
}
