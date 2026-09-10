package com.coverageloop.model;

import java.util.ArrayList;
import java.util.List;

/** Maven 命令预览 */
public class MavenCommandPreview {
    public String executable;
    public List<String> args = new ArrayList<>();
    /** 首轮预构建参数，null 表示不执行预构建 */
    public List<String> preInstallArgs;
    public String workingDirectory;
    public String javaHome;
    /** "bundled" | "configured" | "wrapper" | "environment" */
    public String source;

    public String commandText() {
        List<String> parts = new ArrayList<>();
        parts.add(executable);
        parts.addAll(args);
        return String.join(" ", parts.stream().map(MavenCommandPreview::quoted).toList());
    }

    private static String quoted(String value) {
        return value.matches(".*[\\s\"'].*") ? "\"" + value.replace("\"", "\\\"") + "\"" : value;
    }
}
