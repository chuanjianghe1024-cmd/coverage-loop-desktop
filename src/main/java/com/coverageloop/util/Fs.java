package com.coverageloop.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** 文件系统工具 */
public final class Fs {

    private Fs() {
    }

    public static boolean exists(String path) {
        return path != null && Files.exists(Path.of(path));
    }

    public static boolean isFile(String path) {
        return path != null && Files.isRegularFile(Path.of(path));
    }

    public static boolean isDirectory(String path) {
        return path != null && Files.isDirectory(Path.of(path));
    }

    public static void mkdirs(String path) {
        try {
            Files.createDirectories(Path.of(path));
        } catch (IOException error) {
            throw new RuntimeException("创建目录失败：" + path, error);
        }
    }

    public static String readString(String path) {
        try {
            return Files.readString(Path.of(path), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new RuntimeException("读取文件失败：" + path, error);
        }
    }

    public static String readStringQuiet(String path) {
        try {
            return Files.readString(Path.of(path), StandardCharsets.UTF_8);
        } catch (IOException error) {
            return null;
        }
    }

    public static void writeString(String path, String content) {
        try {
            Path target = Path.of(path);
            if (target.getParent() != null) Files.createDirectories(target.getParent());
            Files.writeString(target, content, StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new RuntimeException("写入文件失败：" + path, error);
        }
    }

    public static void appendString(String path, String content) {
        try {
            Path target = Path.of(path);
            if (target.getParent() != null) Files.createDirectories(target.getParent());
            Files.writeString(target, content, StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException error) {
            throw new RuntimeException("追加写入失败：" + path, error);
        }
    }

    public static void copy(String source, String destination) {
        try {
            Path target = Path.of(destination);
            if (target.getParent() != null) Files.createDirectories(target.getParent());
            Files.copy(Path.of(source), target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException error) {
            throw new RuntimeException("复制文件失败：" + source, error);
        }
    }

    public static void delete(String path) {
        try {
            Files.deleteIfExists(Path.of(path));
        } catch (IOException error) {
            throw new RuntimeException("删除文件失败：" + path, error);
        }
    }

    public static long lastModifiedMs(String path) {
        try {
            return Files.getLastModifiedTime(Path.of(path)).toMillis();
        } catch (IOException error) {
            return 0;
        }
    }

    public static long size(String path) {
        try {
            return Files.size(Path.of(path));
        } catch (IOException error) {
            return 0;
        }
    }

    public static List<Path> listFiles(String directory) {
        try (Stream<Path> stream = Files.list(Path.of(directory))) {
            return stream.toList();
        } catch (IOException error) {
            return List.of();
        }
    }

    public static List<Path> listDirectories(String directory) {
        try (Stream<Path> stream = Files.list(Path.of(directory))) {
            return stream.filter(Files::isDirectory).toList();
        } catch (IOException error) {
            return List.of();
        }
    }

    /** 递归列出目录下所有文件 */
    public static List<Path> walkFiles(String directory) {
        List<Path> result = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(Path.of(directory))) {
            stream.filter(Files::isRegularFile).forEach(result::add);
        } catch (IOException error) {
            // 目录不存在时返回空列表
        }
        return result;
    }
}
