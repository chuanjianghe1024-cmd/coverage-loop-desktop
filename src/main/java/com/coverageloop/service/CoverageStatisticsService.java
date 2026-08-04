package com.coverageloop.service;

import com.coverageloop.model.CoverageClassResult;
import com.coverageloop.model.CoverageModuleResult;
import com.coverageloop.model.CoverageScope;
import com.coverageloop.model.CoverageStatisticsConfig;
import com.coverageloop.model.CoverageStatisticsGroup;
import com.coverageloop.model.CoverageStatisticsGroupResult;
import com.coverageloop.model.CoverageStatisticsModuleResult;
import com.coverageloop.model.CoverageStatisticsPackageResult;
import com.coverageloop.model.CoverageStatisticsSnapshotInfo;
import com.coverageloop.model.CoverageStatisticsState;
import com.coverageloop.model.CoverageStatisticsSummary;
import com.coverageloop.model.MavenRunResult;
import com.coverageloop.model.ConfigFactory;
import com.coverageloop.model.ProjectConfig;
import com.coverageloop.model.ProjectScanResult;
import com.coverageloop.util.Fs;
import com.coverageloop.util.Json;
import com.coverageloop.util.Names;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 覆盖率统计（多分组跨模块），与 Electron 版 coverage-statistics.ts 一致 */
public final class CoverageStatisticsService {

    private CoverageStatisticsService() {
    }

    public static class StoredStatisticsSnapshot {
        public CoverageStatisticsSnapshotInfo snapshot;
        public List<CoverageModuleResult> coverage = new ArrayList<>();
    }

    public static class LatestStatisticsPointer {
        public String snapshotPath;
    }

    public static String statisticsRoot(String rootPomPath) {
        return new File(new File(rootPomPath).getAbsoluteFile().getParent(),
                ".coverage-loop/statistics").getPath();
    }

    public static String statisticsConfigPath(String rootPomPath) {
        return new File(statisticsRoot(rootPomPath), "config.json").getPath();
    }

    private static String latestPointerPath(String rootPomPath) {
        return new File(statisticsRoot(rootPomPath), "latest.json").getPath();
    }

    /** 统计执行模块：所有分组所选模块的并集（只保留有主源码的模块） */
    public static List<String> coverageStatisticsExecutionModulePaths(ProjectScanResult project,
                                                                      CoverageStatisticsConfig config) {
        Set<String> validModulePaths = new HashSet<>();
        for (var module : project.modules) {
            if (module.hasMainSources) validModulePaths.add(module.relativePath);
        }
        Set<String> result = new HashSet<>();
        for (CoverageStatisticsGroup group : config.groups) {
            for (String modulePath : group.modulePaths) {
                if (validModulePaths.contains(modulePath)) result.add(modulePath);
            }
        }
        return new ArrayList<>(result);
    }

    private static CoverageStatisticsGroup normalizeGroup(CoverageStatisticsGroup group, int index) {
        CoverageStatisticsGroup result = new CoverageStatisticsGroup();
        result.id = Names.normalizedId(group.id, "group-" + (index + 1));
        result.name = group.name == null || group.name.trim().isEmpty() ? "分组 " + (index + 1) : group.name.trim();
        List<String> modulePaths = new ArrayList<>(new LinkedHashSet<>(group.modulePaths.stream()
                .filter(path -> path != null && !path.isEmpty()).toList()));
        result.modulePaths = modulePaths;
        List<CoverageScope> scopes = new ArrayList<>();
        for (CoverageScope scope : group.scopes) {
            if (modulePaths.contains(scope.modulePath)) scopes.add(scope);
        }
        result.scopes = scopes;
        result.confirmedAt = group.confirmedAt == null || group.confirmedAt.isEmpty() ? null : group.confirmedAt;
        return result;
    }

    private static CoverageStatisticsConfig normalizeConfig(CoverageStatisticsConfig config) {
        CoverageStatisticsConfig result = new CoverageStatisticsConfig();
        result.schemaVersion = 2;
        result.rootPomPath = config.rootPomPath;
        List<CoverageStatisticsGroup> groups = new ArrayList<>();
        for (int i = 0; i < config.groups.size(); i++) {
            groups.add(normalizeGroup(config.groups.get(i), i));
        }
        if (groups.isEmpty()) {
            result.groups = ConfigFactory.createDefaultCoverageStatistics(config.rootPomPath).groups;
        } else {
            result.groups = groups;
        }
        result.updatedAt = Names.nowIso();
        return result;
    }

    public static String saveCoverageStatisticsConfig(CoverageStatisticsConfig config) {
        CoverageStatisticsConfig normalized = normalizeConfig(config);
        String path = statisticsConfigPath(normalized.rootPomPath);
        Fs.mkdirs(new File(path).getParent());
        Fs.writeString(path, Json.toJson(normalized) + "\n");
        return path;
    }

    private static CoverageStatisticsConfig loadConfig(String rootPomPath) {
        String path = statisticsConfigPath(rootPomPath);
        if (!Fs.exists(path)) return ConfigFactory.createDefaultCoverageStatistics(rootPomPath);
        CoverageStatisticsConfig parsed = Json.fromJson(Fs.readString(path), CoverageStatisticsConfig.class);
        if (parsed == null || (parsed.schemaVersion != 1 && parsed.schemaVersion != 2)
                || parsed.groups == null) {
            throw new IllegalArgumentException("暂不支持当前覆盖率统计配置版本");
        }
        CoverageStatisticsConfig config = new CoverageStatisticsConfig();
        config.schemaVersion = 2;
        config.rootPomPath = rootPomPath;
        config.groups = parsed.groups;
        config.updatedAt = parsed.updatedAt == null ? Names.nowIso() : parsed.updatedAt;
        return normalizeConfig(config);
    }

    private static Double ratio(int covered, int missed) {
        int total = covered + missed;
        if (total == 0) return null;
        return Math.round((covered / (double) total) * 10000.0) / 100.0;
    }

    private static String classKey(CoverageClassResult item) {
        return item.modulePath + "::" + item.qualifiedName;
    }

    private static class Aggregate {
        int classCount;
        int coveredLines;
        int missedLines;
        Double lineCoverage;
    }

    private static Aggregate aggregateClasses(List<CoverageClassResult> classes) {
        Map<String, CoverageClassResult> unique = new LinkedHashMap<>();
        for (CoverageClassResult item : classes) unique.put(classKey(item), item);
        Aggregate aggregate = new Aggregate();
        aggregate.classCount = unique.size();
        for (CoverageClassResult item : unique.values()) {
            aggregate.coveredLines += item.coveredLines;
            aggregate.missedLines += item.missedLines;
        }
        aggregate.lineCoverage = ratio(aggregate.coveredLines, aggregate.missedLines);
        return aggregate;
    }

    private static List<CoverageStatisticsPackageResult> packageResults(List<CoverageClassResult> classes) {
        Map<String, List<CoverageClassResult>> byPackage = new LinkedHashMap<>();
        for (CoverageClassResult item : classes) {
            byPackage.computeIfAbsent(item.packageName, key -> new ArrayList<>()).add(item);
        }
        List<String> names = new ArrayList<>(byPackage.keySet());
        names.sort(String::compareTo);
        List<CoverageStatisticsPackageResult> results = new ArrayList<>();
        for (String packageName : names) {
            List<CoverageClassResult> values = byPackage.get(packageName);
            values.sort((a, b) -> a.qualifiedName.compareTo(b.qualifiedName));
            CoverageStatisticsPackageResult result = new CoverageStatisticsPackageResult();
            result.packageName = packageName;
            Aggregate aggregate = aggregateClasses(values);
            result.classCount = aggregate.classCount;
            result.coveredLines = aggregate.coveredLines;
            result.missedLines = aggregate.missedLines;
            result.lineCoverage = aggregate.lineCoverage;
            result.classes = values;
            results.add(result);
        }
        return results;
    }

    private static CoverageStatisticsModuleResult moduleResult(CoverageModuleResult module,
                                                               CoverageStatisticsGroup group) {
        List<CoverageScope> scopes = new ArrayList<>();
        for (CoverageScope scope : group.scopes) {
            if (scope.modulePath.equals(module.modulePath)) scopes.add(scope);
        }
        List<CoverageClassResult> classes = scopes.isEmpty()
                ? module.classes
                : module.classes.stream()
                        .filter(item -> ScopeSelection.isSelectedClass(scopes, item.qualifiedName)).toList();
        CoverageStatisticsModuleResult result = new CoverageStatisticsModuleResult();
        result.modulePath = module.modulePath;
        result.source = module.source;
        Aggregate aggregate = aggregateClasses(classes);
        result.classCount = aggregate.classCount;
        result.coveredLines = aggregate.coveredLines;
        result.missedLines = aggregate.missedLines;
        result.lineCoverage = aggregate.lineCoverage;
        result.packages = packageResults(classes);
        return result;
    }

    public static CoverageStatisticsSummary buildCoverageStatisticsSummary(CoverageStatisticsConfig config,
                                                                           List<CoverageModuleResult> coverage,
                                                                           String snapshotId) {
        Map<String, CoverageModuleResult> coverageByModule = new HashMap<>();
        for (CoverageModuleResult item : coverage) coverageByModule.put(item.modulePath, item);
        Map<String, CoverageClassResult> allSelectedClasses = new LinkedHashMap<>();
        List<CoverageStatisticsGroupResult> groups = new ArrayList<>();

        for (CoverageStatisticsGroup group : config.groups) {
            if (group.confirmedAt == null) continue;
            List<CoverageStatisticsModuleResult> modules = new ArrayList<>();
            for (String modulePath : group.modulePaths) {
                CoverageModuleResult item = coverageByModule.get(modulePath);
                if (item == null) continue;
                modules.add(moduleResult(item, group));
            }
            List<CoverageClassResult> groupClasses = new ArrayList<>();
            for (CoverageStatisticsModuleResult module : modules) {
                for (CoverageStatisticsPackageResult packageItem : module.packages) {
                    groupClasses.addAll(packageItem.classes);
                }
            }
            for (CoverageClassResult item : groupClasses) allSelectedClasses.put(classKey(item), item);
            CoverageStatisticsGroupResult groupResult = new CoverageStatisticsGroupResult();
            groupResult.groupId = group.id;
            groupResult.groupName = group.name;
            groupResult.confirmedAt = group.confirmedAt;
            groupResult.moduleCount = modules.size();
            Aggregate aggregate = aggregateClasses(groupClasses);
            groupResult.classCount = aggregate.classCount;
            groupResult.coveredLines = aggregate.coveredLines;
            groupResult.missedLines = aggregate.missedLines;
            groupResult.lineCoverage = aggregate.lineCoverage;
            groupResult.modules = modules;
            groups.add(groupResult);
        }

        Aggregate total = aggregateClasses(new ArrayList<>(allSelectedClasses.values()));
        CoverageStatisticsSummary summary = new CoverageStatisticsSummary();
        summary.snapshotId = snapshotId;
        summary.generatedAt = Names.nowIso();
        summary.confirmedGroupCount = groups.size();
        summary.classCount = total.classCount;
        summary.coveredLines = total.coveredLines;
        summary.missedLines = total.missedLines;
        summary.lineCoverage = total.lineCoverage;
        summary.groups = groups;
        return summary;
    }

    private static StoredStatisticsSnapshot readLatestSnapshot(String rootPomPath) {
        String pointerPath = latestPointerPath(rootPomPath);
        if (!Fs.exists(pointerPath)) return null;
        LatestStatisticsPointer pointer = Json.fromJson(Fs.readString(pointerPath), LatestStatisticsPointer.class);
        if (pointer == null || pointer.snapshotPath == null || !Fs.exists(pointer.snapshotPath)) return null;
        return Json.fromJson(Fs.readString(pointer.snapshotPath), StoredStatisticsSnapshot.class);
    }

    public static CoverageStatisticsState loadCoverageStatisticsState(String rootPomPath) {
        CoverageStatisticsConfig config = loadConfig(rootPomPath);
        StoredStatisticsSnapshot stored = readLatestSnapshot(rootPomPath);
        CoverageStatisticsState state = new CoverageStatisticsState();
        state.config = config;
        state.snapshot = stored == null ? null : stored.snapshot;
        state.summary = stored == null ? null
                : buildCoverageStatisticsSummary(config, stored.coverage, stored.snapshot.runId);
        return state;
    }

    /** 确认分组后重算（不重新运行 Maven），复用最近一次快照 */
    public static CoverageStatisticsState recalculateCoverageStatistics(CoverageStatisticsConfig config) {
        CoverageStatisticsConfig normalized = normalizeConfig(config);
        saveCoverageStatisticsConfig(normalized);
        StoredStatisticsSnapshot stored = readLatestSnapshot(normalized.rootPomPath);
        CoverageStatisticsState state = new CoverageStatisticsState();
        state.config = normalized;
        state.snapshot = stored == null ? null : stored.snapshot;
        if (stored != null && stored.snapshot != null) {
            state.summary = buildCoverageStatisticsSummary(normalized, stored.coverage, stored.snapshot.runId);
            Fs.writeString(new File(stored.snapshot.runDirectory, "statistics-summary.json").getPath(),
                    Json.toJson(state.summary) + "\n");
        } else {
            state.summary = null;
        }
        return state;
    }

    public static class PersistedStatistics {
        public CoverageStatisticsSnapshotInfo snapshot;
        public CoverageStatisticsSummary summary;
    }

    /** 统计执行完成后保存快照与指针 */
    public static PersistedStatistics persistCoverageStatisticsSnapshot(CoverageStatisticsConfig config,
                                                                        MavenRunResult result) {
        CoverageStatisticsConfig normalized = normalizeConfig(config);
        saveCoverageStatisticsConfig(normalized);
        String snapshotPath = new File(result.runDirectory, "statistics-coverage.json").getPath();

        CoverageStatisticsSnapshotInfo snapshot = new CoverageStatisticsSnapshotInfo();
        snapshot.runId = result.runId;
        snapshot.startedAt = result.startedAt;
        snapshot.finishedAt = result.finishedAt;
        snapshot.logPath = result.logPath;
        snapshot.runDirectory = result.runDirectory;
        snapshot.coverageSnapshotPath = snapshotPath;
        snapshot.moduleCount = result.coverage.size();
        snapshot.tests = result.tests;

        StoredStatisticsSnapshot stored = new StoredStatisticsSnapshot();
        stored.snapshot = snapshot;
        stored.coverage = result.coverage;
        CoverageStatisticsSummary summary = buildCoverageStatisticsSummary(normalized, result.coverage, result.runId);
        Fs.mkdirs(statisticsRoot(normalized.rootPomPath));
        Fs.writeString(snapshotPath, Json.toJson(stored) + "\n");
        Fs.writeString(new File(result.runDirectory, "statistics-summary.json").getPath(),
                Json.toJson(summary) + "\n");
        LatestStatisticsPointer pointer = new LatestStatisticsPointer();
        pointer.snapshotPath = snapshotPath;
        Fs.writeString(latestPointerPath(normalized.rootPomPath), Json.toJson(pointer) + "\n");

        PersistedStatistics persisted = new PersistedStatistics();
        persisted.snapshot = snapshot;
        persisted.summary = summary;
        return persisted;
    }
}
