package com.coverageloop.service;

import com.coverageloop.model.CoverageClassResult;
import com.coverageloop.model.CoverageModuleResult;
import com.coverageloop.model.CoverageScope;
import com.coverageloop.model.CoverageScopeResult;
import com.coverageloop.model.MavenModule;
import com.coverageloop.model.ProjectConfig;
import com.coverageloop.model.ProjectScanResult;
import com.coverageloop.model.ScopeKind;
import com.coverageloop.model.ScopeMode;
import com.coverageloop.util.Fs;
import com.coverageloop.util.XmlUtil;
import org.w3c.dom.Element;

import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 读取 JaCoCo 报告与源码兜底覆盖率，与 Electron 版 coverage-reader.ts 一致 */
public final class CoverageReader {

    private static final Pattern PACKAGE_PATTERN =
            Pattern.compile("^\\s*package\\s+([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*)\\s*;", Pattern.MULTILINE);

    private CoverageReader() {
    }

    private static Double ratio(int covered, int missed) {
        int total = covered + missed;
        if (total == 0) return null;
        return Math.round((covered / (double) total) * 10000.0) / 100.0;
    }

    /** 汇总配置范围：返回选中类与各 Include 范围的结果（Exclude 优先扣除） */
    public static Summarized summarizeConfiguredScopes(String modulePath, List<CoverageScope> scopes,
                                                       List<CoverageClassResult> allClasses) {
        List<CoverageClassResult> selectedClasses = new ArrayList<>();
        for (CoverageClassResult item : allClasses) {
            if (ScopeSelection.isSelectedClass(scopes, item.qualifiedName)) selectedClasses.add(item);
        }
        List<CoverageScope> includes = scopes.stream().filter(s -> s.mode == ScopeMode.include).toList();
        List<CoverageScope> excludes = scopes.stream().filter(s -> s.mode == ScopeMode.exclude).toList();
        List<CoverageScopeResult> scopeResults = new ArrayList<>();
        if (includes.isEmpty()) {
            scopeResults.add(aggregateScope(modulePath, "module", modulePath, selectedClasses));
        } else {
            for (CoverageScope scope : includes) {
                List<CoverageClassResult> classes = new ArrayList<>();
                for (CoverageClassResult item : allClasses) {
                    if (ScopeSelection.scopeMatchesClass(scope, item.qualifiedName)
                            && excludes.stream().noneMatch(exclude -> ScopeSelection.scopeMatchesClass(exclude, item.qualifiedName))) {
                        classes.add(item);
                    }
                }
                scopeResults.add(aggregateScope(modulePath, scope.kind.json(), scope.pattern, classes));
            }
        }
        Summarized summarized = new Summarized();
        summarized.selectedClasses = selectedClasses;
        summarized.scopeResults = scopeResults;
        return summarized;
    }

    private static CoverageScopeResult aggregateScope(String modulePath, String kind, String pattern,
                                                      List<CoverageClassResult> classes) {
        int coveredLines = 0;
        int missedLines = 0;
        for (CoverageClassResult item : classes) {
            coveredLines += item.coveredLines;
            missedLines += item.missedLines;
        }
        CoverageScopeResult result = new CoverageScopeResult();
        result.modulePath = modulePath;
        result.kind = kind;
        result.pattern = pattern;
        result.classCount = classes.size();
        result.coveredLines = coveredLines;
        result.missedLines = missedLines;
        result.lineCoverage = ratio(coveredLines, missedLines);
        return result;
    }

    public static class Summarized {
        public List<CoverageClassResult> selectedClasses = new ArrayList<>();
        public List<CoverageScopeResult> scopeResults = new ArrayList<>();
    }

    /** 没有新鲜 JaCoCo 报告时，从源码范围建立明确标记的 0% 兜底结果 */
    private static CoverageModuleResult readSourceFallback(String modulePath, String sourceRoot,
                                                           List<CoverageScope> scopes) {
        List<CoverageClassResult> allClasses = new ArrayList<>();
        File root = new File(sourceRoot);
        if (root.isDirectory()) {
            scanSourceDirectory(root, root, modulePath, allClasses);
        }
        Summarized summarized = summarizeConfiguredScopes(modulePath, scopes, allClasses);
        int missedLines = 0;
        for (CoverageClassResult item : summarized.selectedClasses) missedLines += item.missedLines;

        CoverageModuleResult result = new CoverageModuleResult();
        result.modulePath = modulePath;
        result.reportPath = null;
        result.source = "source-fallback";
        result.classCount = summarized.selectedClasses.size();
        result.coveredLines = 0;
        result.missedLines = missedLines;
        result.lineCoverage = summarized.selectedClasses.isEmpty() ? null : 0.0;
        result.classes = summarized.selectedClasses;
        result.classes.sort((a, b) -> a.qualifiedName.compareTo(b.qualifiedName));
        result.scopeResults = summarized.scopeResults;
        return result;
    }

    private static void scanSourceDirectory(File directory, File sourceRoot, String modulePath,
                                            List<CoverageClassResult> allClasses) {
        File[] entries = directory.listFiles();
        if (entries == null) return;
        for (File entry : entries) {
            if (entry.isDirectory()) {
                scanSourceDirectory(entry, sourceRoot, modulePath, allClasses);
                continue;
            }
            if (!entry.isFile() || !entry.getName().endsWith(".java")) continue;
            String content = Fs.readStringQuiet(entry.getPath());
            if (content == null) continue;
            String declaredPackage = "";
            Matcher matcher = PACKAGE_PATTERN.matcher(content);
            if (matcher.find()) declaredPackage = matcher.group(1);
            String relativePath = relativePath(sourceRoot.getPath(), entry.getPath());
            String fallbackPackage = "";
            int separator = relativePath.lastIndexOf('/');
            if (separator > 0) {
                fallbackPackage = relativePath.substring(0, separator).replace('/', '.');
            }
            String packageName = declaredPackage.isEmpty() ? fallbackPackage : declaredPackage;
            String className = entry.getName().substring(0, entry.getName().length() - 5);
            int missedLines = Math.max(1, countSourceLines(content));
            CoverageClassResult item = new CoverageClassResult();
            item.modulePath = modulePath;
            item.packageName = packageName;
            item.className = className;
            item.qualifiedName = packageName.isEmpty() ? className : packageName + "." + className;
            item.coveredLines = 0;
            item.missedLines = missedLines;
            item.lineCoverage = 0.0;
            allClasses.add(item);
        }
    }

    /** 统计源码非空、非注释、非括号、非 package/import 的行数 */
    static int countSourceLines(String content) {
        boolean blockComment = false;
        int count = 0;
        for (String originalLine : content.split("\\r?\\n")) {
            String line = originalLine;
            if (blockComment) {
                int end = line.indexOf("*/");
                if (end < 0) continue;
                blockComment = false;
                line = line.substring(end + 2);
            }
            while (line.contains("/*")) {
                int start = line.indexOf("/*");
                int end = line.indexOf("*/", start + 2);
                if (end < 0) {
                    blockComment = true;
                    line = line.substring(0, start);
                    break;
                }
                line = line.substring(0, start) + line.substring(end + 2);
            }
            String value = line.replaceAll("//.*$", "").trim();
            if (value.isEmpty() || value.equals("{") || value.equals("}") || value.matches("^package\\s|^import\\s")) {
                continue;
            }
            count += 1;
        }
        return count;
    }

    private static String relativePath(String root, String path) {
        String relative = new File(root).toPath().toAbsolutePath().normalize()
                .relativize(new File(path).toPath().toAbsolutePath().normalize())
                .toString().replace(File.separatorChar, '/');
        return relative.isEmpty() ? "." : relative;
    }

    /** 读取所选模块的 JaCoCo 覆盖率；报告缺失或过期时使用 0% 源码兜底 */
    public static List<CoverageModuleResult> readCoverageResults(ProjectConfig config, String startedAt) {
        String rootDirectory = new File(config.rootPomPath).getAbsoluteFile().getParent();
        ProjectScanResult project = ProjectScanner.scanMavenProject(config.rootPomPath);
        Map<String, String> sourceRoots = new HashMap<>();
        for (MavenModule module : project.modules) sourceRoots.put(module.relativePath, module.sourceRoot);

        List<CoverageModuleResult> results = new ArrayList<>();
        for (String modulePath : config.selectedModulePaths) {
            String moduleDirectory = ".".equals(modulePath) ? rootDirectory : new File(rootDirectory, modulePath).getPath();
            String reportPath = new File(moduleDirectory, "target/site/jacoco/jacoco.xml").getPath();
            List<CoverageScope> scopes = new ArrayList<>();
            for (CoverageScope scope : config.scopes) {
                if (scope.modulePath.equals(modulePath)) scopes.add(scope);
            }
            boolean reportExists = Fs.exists(reportPath);
            boolean reportIsFresh = reportExists
                    && (startedAt == null || Fs.lastModifiedMs(reportPath) + 2_000 >= Instant.parse(startedAt).toEpochMilli());
            if (!reportIsFresh) {
                String sourceRoot = sourceRoots.getOrDefault(modulePath, new File(moduleDirectory, "src/main/java").getPath());
                results.add(readSourceFallback(modulePath, sourceRoot, scopes));
                continue;
            }

            Element report = XmlUtil.parse(Fs.readString(reportPath));
            List<CoverageClassResult> allClasses = new ArrayList<>();
            if (report != null) {
                for (Element packageNode : XmlUtil.list(report, "package")) {
                    for (Element classNode : XmlUtil.list(packageNode, "class")) {
                        String internalName = XmlUtil.attr(classNode, "name");
                        if (internalName.isEmpty()) continue;
                        String qualifiedName = internalName.replace('/', '.');
                        LineCounter line = parseLineCounter(classNode);
                        int separator = qualifiedName.lastIndexOf('.');
                        CoverageClassResult item = new CoverageClassResult();
                        item.modulePath = modulePath;
                        item.packageName = separator < 0 ? "" : qualifiedName.substring(0, separator);
                        item.className = separator < 0 ? qualifiedName : qualifiedName.substring(separator + 1);
                        item.qualifiedName = qualifiedName;
                        item.coveredLines = line.covered;
                        item.missedLines = line.missed;
                        item.lineCoverage = ratio(line.covered, line.missed);
                        allClasses.add(item);
                    }
                }
            }

            Summarized summarized = summarizeConfiguredScopes(modulePath, scopes, allClasses);
            int coveredLines = 0;
            int missedLines = 0;
            for (CoverageClassResult item : summarized.selectedClasses) {
                coveredLines += item.coveredLines;
                missedLines += item.missedLines;
            }
            CoverageModuleResult result = new CoverageModuleResult();
            result.modulePath = modulePath;
            result.reportPath = reportPath;
            result.source = "jacoco";
            result.classCount = summarized.selectedClasses.size();
            result.coveredLines = coveredLines;
            result.missedLines = missedLines;
            result.lineCoverage = ratio(coveredLines, missedLines);
            result.classes = summarized.selectedClasses;
            result.classes.sort((a, b) -> a.qualifiedName.compareTo(b.qualifiedName));
            result.scopeResults = summarized.scopeResults;
            results.add(result);
        }
        return results;
    }

    private static class LineCounter {
        int covered;
        int missed;
    }

    private static LineCounter parseLineCounter(Element classNode) {
        LineCounter result = new LineCounter();
        for (Element counter : XmlUtil.list(classNode, "counter")) {
            if (!"LINE".equals(XmlUtil.attr(counter, "type"))) continue;
            result.covered = intValue(XmlUtil.attr(counter, "covered"));
            result.missed = intValue(XmlUtil.attr(counter, "missed"));
        }
        return result;
    }

    private static int intValue(String value) {
        if (value.isEmpty()) return 0;
        try {
            return (int) Double.parseDouble(value);
        } catch (NumberFormatException error) {
            return 0;
        }
    }
}
