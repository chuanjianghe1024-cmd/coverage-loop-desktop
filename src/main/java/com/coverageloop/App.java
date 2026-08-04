package com.coverageloop;

import com.coverageloop.ui.AppContext;
import com.coverageloop.ui.MainView;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.stage.Stage;

/** JavaFX 应用入口 */
public class App extends Application {

    @Override
    public void start(Stage stage) {
        AppContext context = new AppContext(stage);
        MainView mainView = new MainView(context);
        Scene scene = new Scene(mainView.build(), 1380, 860);
        scene.getStylesheets().add(getClass().getResource("/com/coverageloop/styles.css").toExternalForm());
        stage.setTitle("Coverage Loop Desktop - 自动补测工作台");
        stage.setScene(scene);
        stage.setMinWidth(1080);
        stage.setMinHeight(700);
        stage.show();
        stage.setOnCloseRequest(event -> {
            context.shutdown();
            Platform.exit();
        });
    }

    public static void main(String[] args) {
        launch(args);
    }
}
