package com.coverageloop.service;

import com.coverageloop.model.CoverageClassResult;
import com.coverageloop.model.CoverageGroupedClass;
import com.coverageloop.model.CoverageGroups;
import com.coverageloop.model.CoverageModuleResult;
import com.coverageloop.model.ProjectConfig;
import com.coverageloop.model.TestExecutionResult;
import com.coverageloop.util.Fs;
import com.coverageloop.util.Json;
import com.coverageloop.util.Names;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 执行记录与覆盖率基线，与 Electron 版 run-history.ts 一致 */
public final class RunHistory {

    private RunHistory() {
    }

    public static class PreparedRound {
        public final String runId;
        public final int round;
        public final String runDirectory;
        public final String logPath;

        public PreparedRound(String runId, int round, String runDirectory, String logPath) {
            this.runId = runId;
            this.round = round;
            this.runDirectory = runDirectory;
            this.logPath = logPath;
        }
    }

    public static class CoverageBaseline {
        public String createdAt;
        public List<CoverageClassResult> classes = new ArrayList<>();
    }

    public static PreparedRound prepareRound(ProjectConfig config, String sessionId) {
        String projectRoot = new File(config.rootPomPath).getAbsoluteFile().getParent();
        String runsRoot = new File(new File(projectRoot, ".coverage-loop"), "runs")
                .getPath() + File.separator + Names.normalizedIdOrThrow(config.id, "配置 ID");
        String runId = sessionId == null || sessionId.isBlank()
                ? Names.newRunId() : Names.normalizedIdOrThrow(sessionId, "执行 ID");
        String runDirectory = new File(runsRoot, runId).getPath();

        if (sessionId != null && !sessionId.isBlank() && !Fs.isDirectory(runDirectory)) {
            throw new IllegalArgumentException("找不到要继续的执行记录：" + sessionId);
        }
        Fs.mkdirs(runDirectory);

        int maxRound = 0;
        for (File entry : new File(runDirectory).listFiles()) {
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("^round-(\\d{3})-maven\\.log$").matcher(entry.getName());
            if (matcher.matches()) {
                maxRound = Math.max(maxRound, Integer.parseInt(matcher.group(1)));
            }
        }
        int round = maxRound + 1;
        return new PreparedRound(runId, round, runDirectory,
                new File(runDirectory, "round-" + Names.roundLabel(round) + "-maven.log").getPath());
    }

    private static String classKey(CoverageClassResult item) {
        return item.modulePath + "::" + item.qualifiedName;
    }

    private static List<CoverageClassResult> flattenClasses(List<CoverageModuleResult> coverage) {
        List<CoverageClassResult> result = new ArrayList<>();
        for (CoverageModuleResult module : coverage) result.addAll(module.classes);
        return result;
    }

    /** 类级分组：初始已满足 / 待补充 / 已补充 */
    public static CoverageGroups buildCoverageGroups(CoverageBaseline baseline,
                                                     List<CoverageModuleResult> coverage, int threshold) {
        Map<String, CoverageClassResult> baselineMap = new HashMap<>();
        for (CoverageClassResult item : baseline.classes) baselineMap.put(classKey(item), item);
        Map<String, CoverageClassResult> currentMap = new HashMap<>();
        for (CoverageClassResult item : flattenClasses(coverage)) currentMap.put(classKey(item), item);
        java.util.Set<String> keys = new java.util.HashSet<>(baselineMap.keySet());
        keys.addAll(currentMap.keySet());

        CoverageGroups groups = new CoverageGroups();
        groups.threshold = threshold;
        groups.baselineCreatedAt = baseline.createdAt;
        for (String key : keys) {
            CoverageClassResult baselineClass = baselineMap.get(key);
            CoverageClassResult currentClass = currentMap.get(key);
            CoverageClassResult source = currentClass != null ? currentClass : baselineClass;
            if (source == null) continue;
            double initialCoverage = baselineClass != null && baselineClass.lineCoverage != null
                    ? baselineClass.lineCoverage : -1;
            double currentCoverage = currentClass != null && currentClass.lineCoverage != null
                    ? currentClass.lineCoverage : -1;
            CoverageGroupedClass item = new CoverageGroupedClass(source,
                    baselineClass != null ? baselineClass.lineCoverage : null,
                    currentClass != null ? currentClass.lineCoverage : null);
            item.coveredLines = currentClass != null ? currentClass.coveredLines : 0;
            item.missedLines = currentClass != null ? currentClass.missedLines
                    : (baselineClass != null ? baselineClass.coveredLines + baselineClass.missedLines : 0);
            item.lineCoverage = currentClass != null ? currentClass.lineCoverage : null;
            boolean initialPassed = initialCoverage >= threshold;
            boolean currentPassed = currentCoverage >= threshold;
            if (!currentPassed) groups.pending.add(item);
            else if (initialPassed) groups.initialSatisfied.add(item);
            else groups.supplemented.add(item);
        }

        Comparator<CoverageGroupedClass> byCoverage = (a, b) -> {
            double aCoverage = a.currentLineCoverage != null ? a.currentLineCoverage : -1;
            double bCoverage = b.currentLineCoverage != null ? b.currentLineCoverage : -1;
            int compared = Double.compare(aCoverage, bCoverage);
            return compared != 0 ? compared : a.qualifiedName.compareTo(b.qualifiedName);
        };
        groups.initialSatisfied.sort(byCoverage);
        groups.pending.sort(byCoverage);
        groups.supplemented.sort(byCoverage);
        return groups;
    }

    /** 保存本轮覆盖率快照、门禁文件、待补类清单与 session 信息 */
    public static PersistedCoverage persistCoverageRound(ProjectConfig config, PreparedRound prepared,
                                                         List<CoverageModuleResult> coverage,
                                                         String startedAt, String finishedAt,
                                                         Integer exitCode, TestExecutionResult tests) {
        Fs.mkdirs(prepared.runDirectory);
        String baselinePath = new File(prepared.runDirectory, "baseline-coverage.json").getPath();
        CoverageBaseline baseline;
        if (Fs.exists(baselinePath)) {
            baseline = Json.fromJson(Fs.readString(baselinePath), CoverageBaseline.class);
        } else {
            baseline = new CoverageBaseline();
            baseline.createdAt = finishedAt;
            baseline.classes = flattenClasses(coverage);
            if (!baseline.classes.isEmpty()) {
                Fs.writeString(baselinePath, Json.toJson(baseline) + "\n");
            }
        }

        CoverageGroups groups = buildCoverageGroups(baseline, coverage, config.coverage.lineThreshold);
        String roundLabel = Names.roundLabel(prepared.round);
        String coverageSnapshotPath = new File(prepared.runDirectory, "round-" + roundLabel + "-coverage.json").getPath();
        String coverageGatePath = new File(prepared.runDirectory, "round-" + roundLabel + "-coverage-gate.txt").getPath();
        String failedClassesPath = new File(prepared.runDirectory, "round-" + roundLabel + "-failed-classes.txt").getPath();

        List<String> failedClassLines = new ArrayList<>();
        int index = 1;
        for (CoverageGroupedClass item : groups.pending) {
            int total = item.coveredLines + item.missedLines;
            failedClassLines.add(String.format("%d. [%s] %s | LINE=%s%% | covered=%d/%d", index++,
                    item.modulePath, item.qualifiedName,
                    item.currentLineCoverage == null ? 0 : item.currentLineCoverage,
                    item.coveredLines, total));
        }
        List<String> gateLines = new ArrayList<>();
        gateLines.add("JaCoCo Coverage Gate - Round " + prepared.round);
        gateLines.add("LINE threshold : " + config.coverage.lineThreshold + "%");
        gateLines.add("Initial passed : " + groups.initialSatisfied.size());
        gateLines.add("Supplemented   : " + groups.supplemented.size());
        gateLines.add("Remaining      : " + groups.pending.size());
        gateLines.add(groups.pending.isEmpty() ? "ALL_TARGETS_PASS" : "COVERAGE_GATE_FAILED");
        gateLines.add("");
        gateLines.addAll(failedClassLines);
        gateLines.add("");
        Fs.writeString(coverageGatePath, String.join("\n", gateLines));

        Fs.writeString(failedClassesPath, failedClassLines.isEmpty() ? "" : String.join("\n", failedClassLines) + "\n");

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("runId", prepared.runId);
        snapshot.put("round", prepared.round);
        snapshot.put("configId", config.id);
        snapshot.put("configName", config.name);
        snapshot.put("startedAt", startedAt);
        snapshot.put("finishedAt", finishedAt);
        snapshot.put("exitCode", exitCode);
        snapshot.put("tests", tests);
        snapshot.put("coverage", coverage);
        snapshot.put("groups", groups);
        Fs.writeString(coverageSnapshotPath, Json.toJson(snapshot) + "\n");

        Map<String, Object> session = new LinkedHashMap<>();
        session.put("runId", prepared.runId);
        session.put("configId", config.id);
        session.put("configName", config.name);
        session.put("latestRound", prepared.round);
        session.put("lineThreshold", config.coverage.lineThreshold);
        session.put("latestTestStatus", tests.status.json());
        session.put("latestSurefireArchivePath", tests.reportArchivePath);
        session.put("baselinePath", baselinePath);
        session.put("latestCoverageSnapshotPath", coverageSnapshotPath);
        session.put("updatedAt", finishedAt);
        Fs.writeString(new File(prepared.runDirectory, "session.json").getPath(), Json.toJson(session) + "\n");

        PersistedCoverage result = new PersistedCoverage();
        result.groups = groups;
        result.coverageSnapshotPath = coverageSnapshotPath;
        return result;
    }

    public static class PersistedCoverage {
        public CoverageGroups groups;
        public String coverageSnapshotPath;
    }
}
