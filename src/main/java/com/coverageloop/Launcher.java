package com.coverageloop;

import javafx.application.Application;

/** 支持 `java -jar` 运行的非 JavaFX 启动器（反射调用 JavaFX 启动器） */
public class Launcher {
    public static void main(String[] args) {
        Application.launch(App.class, args);
    }
}
