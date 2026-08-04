package com.coverageloop.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/** 进程工具：启动、流式输出、整树终止 */
public final class Proc {

    private Proc() {
    }

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    /** .cmd/.bat 需要经 cmd /c 启动；其余直接执行 */
    public static List<String> commandLine(String executable, List<String> args) {
        String lower = executable.toLowerCase();
        if (isWindows() && (lower.endsWith(".cmd") || lower.endsWith(".bat"))) {
            java.util.ArrayList<String> line = new java.util.ArrayList<>();
            line.add("cmd.exe");
            line.add("/c");
            line.add(executable);
            line.addAll(args);
            return line;
        }
        java.util.ArrayList<String> line = new java.util.ArrayList<>();
        line.add(executable);
        line.addAll(args);
        return line;
    }

    public static ProcessBuilder builder(String executable, List<String> args, String workingDirectory,
                                         Map<String, String> extraEnv) {
        ProcessBuilder builder = new ProcessBuilder(commandLine(executable, args));
        builder.directory(new java.io.File(workingDirectory));
        builder.environment().putAll(extraEnv);
        return builder;
    }

    /** 启动进程并流式转发 stdout/stderr，等待结束。返回 {exitCode, signal?} */
    public static ProcessResult run(String executable, List<String> args, String workingDirectory,
                                    Map<String, String> extraEnv,
                                    BiConsumer<byte[], byte[]> output) throws IOException, InterruptedException {
        ProcessBuilder builder = builder(executable, args, workingDirectory, extraEnv);
        builder.redirectErrorStream(false);
        Process process = builder.start();
        Thread stdout = pump(process.getInputStream(), output, true);
        Thread stderr = pump(process.getErrorStream(), output, false);
        int exitCode = process.waitFor();
        stdout.join();
        stderr.join();
        return new ProcessResult(exitCode, null);
    }

    private static Thread pump(java.io.InputStream stream, BiConsumer<byte[], byte[]> output, boolean isStdout) {
        Thread thread = new Thread(() -> {
            byte[] buffer = new byte[8192];
            try {
                int read;
                while ((read = stream.read(buffer)) != -1) {
                    if (read > 0) {
                        byte[] chunk = java.util.Arrays.copyOf(buffer, read);
                        if (isStdout) output.accept(chunk, null);
                        else output.accept(null, chunk);
                    }
                }
            } catch (IOException error) {
                // 进程退出后流关闭，正常结束
            }
        });
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /** 终止整个进程树（Windows 用 taskkill /T /F） */
    public static void killTree(long pid) {
        if (pid <= 0) return;
        if (isWindows()) {
            try {
                new ProcessBuilder("taskkill", "/pid", String.valueOf(pid), "/t", "/f")
                        .redirectErrorStream(true).start();
            } catch (IOException error) {
                // 进程可能已退出
            }
        } else {
            try {
                Runtime.getRuntime().exec(new String[]{"kill", "-TERM", "-" + pid});
            } catch (IOException error) {
                try {
                    Runtime.getRuntime().exec(new String[]{"kill", "-TERM", String.valueOf(pid)});
                } catch (IOException ignored) {
                    // 进程可能已退出
                }
            }
        }
    }

    public static String decode(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public static class ProcessResult {
        public final int exitCode;
        public final String signal;

        public ProcessResult(int exitCode, String signal) {
            this.exitCode = exitCode;
            this.signal = signal;
        }
    }
}
