package com.coverageloop.service;

import com.coverageloop.model.JavaClassInfo;
import com.coverageloop.model.MavenModule;
import com.coverageloop.model.ModuleSources;
import com.coverageloop.model.ProjectScanResult;
import com.coverageloop.util.Fs;
import com.coverageloop.util.XmlUtil;
import org.w3c.dom.Element;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 扫描 Maven Reactor 模块与 Java 源码，与 Electron 版 project-scanner.ts 一致 */
public final class ProjectScanner {

    private static final Pattern PACKAGE_PATTERN =
            Pattern.compile("^\\s*package\\s+([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*)\\s*;", Pattern.MULTILINE);

    private ProjectScanner() {
    }

    /** 递归解析根 POM 的 <modules>（含 profile 中声明的模块） */
    public static ProjectScanResult scanMavenProject(String rootPomInput) {
        File rootPomFile = new File(rootPomInput).getAbsoluteFile();
        String rootPomPath = rootPomFile.getPath();
        if (!rootPomFile.getName().toLowerCase().equals("pom.xml")) {
            throw new IllegalArgumentException("请选择 Maven 根目录下的 pom.xml");
        }
        if (!Fs.exists(rootPomPath)) {
            throw new IllegalArgumentException("找不到 POM：" + rootPomPath);
        }

        String rootDirectory = rootPomFile.getParent();
        Element rootProject = parsePom(rootPomPath);
        String rootArtifactId = XmlUtil.childText(rootProject, "artifactId");
        if (rootArtifactId.isEmpty()) rootArtifactId = rootPomFile.getParentFile().getName();

        ProjectScanResult result = new ProjectScanResult();
        result.rootPomPath = rootPomPath;
        result.rootDirectory = rootDirectory;
        result.rootArtifactId = rootArtifactId;

        Set<String> visited = new HashSet<>();
        visited.add(normalize(rootPomPath).toLowerCase());
        walk(rootPomPath, rootProject, rootDirectory, visited, result);

        result.modules.sort((a, b) -> a.relativePath.compareTo(b.relativePath));
        if (result.modules.isEmpty()) {
            // 单模块项目：没有 <modules> 时把根项目本身作为唯一模块（relativePath="."）
            result.modules.add(buildModule(rootDirectory, rootDirectory, rootPomPath, rootProject));
            result.warnings.add("未发现 <modules>，已将根项目作为单模块处理");
        }
        if (!XmlUtil.list(rootProject, "profiles").isEmpty()) {
            result.warnings.add("已展示 profile 中声明的模块；实际构建时仍需选择对应 Maven Profile");
        }
        return result;
    }

    private static Element parsePom(String pomPath) {
        Element project = XmlUtil.parse(Fs.readString(pomPath));
        if (project == null) throw new IllegalArgumentException("POM 解析失败：" + pomPath);
        return project;
    }

    private static void walk(String parentPom, Element project, String rootDirectory,
                             Set<String> visited, ProjectScanResult result) {
        String parentDirectory = new File(parentPom).getParent();
        for (String declaration : collectModuleDeclarations(project)) {
            String moduleDirectory = new File(parentDirectory, declaration).getPath();
            String modulePom = new File(moduleDirectory, "pom.xml").getPath();
            if (!isWithin(rootDirectory, moduleDirectory)) {
                result.warnings.add("已跳过根目录外模块：" + declaration);
                continue;
            }
            if (!Fs.exists(modulePom)) {
                result.warnings.add("模块缺少 pom.xml：" + relativePath(rootDirectory, moduleDirectory));
                continue;
            }
            String key = normalize(modulePom).toLowerCase();
            if (visited.contains(key)) continue;
            visited.add(key);
            try {
                Element moduleProject = parsePom(modulePom);
                result.modules.add(buildModule(rootDirectory, moduleDirectory, modulePom, moduleProject));
                walk(modulePom, moduleProject, rootDirectory, visited, result);
            } catch (Exception error) {
                result.warnings.add("POM 解析失败 " + relativePath(rootDirectory, modulePom) + "：" + error.getMessage());
            }
        }
    }

    /** 直接模块 + profile 中声明的模块，去重 */
    private static List<String> collectModuleDeclarations(Element project) {
        Set<String> modules = new LinkedHashSet<>();
        for (Element module : XmlUtil.list(project, "modules")) {
            for (Element item : XmlUtil.list(module, "module")) {
                String value = XmlUtil.text(item);
                if (!value.isEmpty()) modules.add(value);
            }
        }
        for (Element profiles : XmlUtil.list(project, "profiles")) {
            for (Element profile : XmlUtil.list(profiles, "profile")) {
                for (Element modulesElement : XmlUtil.list(profile, "modules")) {
                    for (Element item : XmlUtil.list(modulesElement, "module")) {
                        String value = XmlUtil.text(item);
                        if (!value.isEmpty()) modules.add(value);
                    }
                }
            }
        }
        return new ArrayList<>(modules);
    }

    private static MavenModule buildModule(String rootDirectory, String moduleDirectory, String pomPath,
                                           Element project) {
        String artifactId = XmlUtil.childText(project, "artifactId");
        if (artifactId.isEmpty()) artifactId = new File(moduleDirectory).getName();
        String packaging = XmlUtil.childText(project, "packaging");
        if (packaging.isEmpty()) packaging = "jar";

        Element build = XmlUtil.first(project, "build");
        String declaredSource = build == null ? "" : XmlUtil.childText(build, "sourceDirectory");
        String declaredTest = build == null ? "" : XmlUtil.childText(build, "testSourceDirectory");
        String sourceRoot = (!declaredSource.isEmpty() && !declaredSource.contains("${"))
                ? new File(moduleDirectory, declaredSource).getPath()
                : new File(moduleDirectory, "src/main/java").getPath();
        String testRoot = (!declaredTest.isEmpty() && !declaredTest.contains("${"))
                ? new File(moduleDirectory, declaredTest).getPath()
                : new File(moduleDirectory, "src/test/java").getPath();

        MavenModule module = new MavenModule();
        module.name = artifactId;
        module.artifactId = artifactId;
        module.relativePath = relativePath(rootDirectory, moduleDirectory);
        module.pomPath = pomPath;
        module.packaging = packaging;
        module.sourceRoot = sourceRoot;
        module.testRoot = testRoot;
        module.hasMainSources = Fs.isDirectory(sourceRoot);
        module.hasTests = Fs.isDirectory(testRoot);
        return module;
    }

    /** 扫描单个模块的源码与测试文件 */
    public static ModuleSources scanModuleSources(MavenModule module) {
        ScanTreeResult main = scanJavaTree(module.sourceRoot);
        ScanTreeResult tests = scanJavaTree(module.testRoot);
        ModuleSources sources = new ModuleSources();
        sources.modulePath = module.relativePath;
        Set<String> packageSet = new java.util.TreeSet<>();
        for (JavaClassInfo item : main.classes) {
            if (!item.packageName.isEmpty()) packageSet.add(item.packageName);
        }
        sources.packages.addAll(packageSet);
        sources.classes = main.classes;
        sources.testClasses = tests.classes;
        sources.sourceFileCount = main.fileCount;
        sources.testFileCount = tests.fileCount;
        return sources;
    }

    private static class ScanTreeResult {
        final List<JavaClassInfo> classes = new ArrayList<>();
        int fileCount;
    }

    private static ScanTreeResult scanJavaTree(String sourceRoot) {
        ScanTreeResult result = new ScanTreeResult();
        File root = new File(sourceRoot);
        if (!root.isDirectory()) return result;
        scanJavaDirectory(root, root, result);
        result.classes.sort((a, b) -> a.qualifiedName.compareTo(b.qualifiedName));
        return result;
    }

    private static void scanJavaDirectory(File directory, File sourceRoot, ScanTreeResult result) {
        File[] entries = directory.listFiles();
        if (entries == null) return;
        for (File entry : entries) {
            if (entry.isDirectory()) {
                if (!entry.getName().equals("target")) scanJavaDirectory(entry, sourceRoot, result);
                continue;
            }
            if (!entry.isFile() || !entry.getName().endsWith(".java")) continue;
            result.fileCount += 1;
            String relativePath = relativePath(sourceRoot.getPath(), entry.getPath());
            String fallbackPackage = "";
            int separator = relativePath.lastIndexOf('/');
            if (separator > 0) {
                fallbackPackage = relativePath.substring(0, separator).replace('/', '.');
            }
            String packageName = fallbackPackage;
            String content = Fs.readStringQuiet(entry.getPath());
            if (content != null) {
                Matcher matcher = PACKAGE_PATTERN.matcher(content);
                if (matcher.find()) packageName = matcher.group(1);
            }
            String name = entry.getName().substring(0, entry.getName().length() - 5);
            JavaClassInfo info = new JavaClassInfo();
            info.name = name;
            info.packageName = packageName;
            info.qualifiedName = packageName.isEmpty() ? name : packageName + "." + name;
            info.relativePath = relativePath;
            result.classes.add(info);
        }
    }

    private static String normalize(String path) {
        return Path.of(path).normalize().toString();
    }

    private static boolean isWithin(String parent, String child) {
        Path parentPath = Path.of(parent).toAbsolutePath().normalize();
        Path childPath = Path.of(child).toAbsolutePath().normalize();
        return childPath.startsWith(parentPath);
    }

    private static String relativePath(String root, String path) {
        Path relative = Path.of(root).toAbsolutePath().normalize()
                .relativize(Path.of(path).toAbsolutePath().normalize());
        String value = relative.toString().replace(File.separatorChar, '/');
        return value.isEmpty() ? "." : value;
    }
}
