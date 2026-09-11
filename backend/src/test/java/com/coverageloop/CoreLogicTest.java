package com.coverageloop;

import com.coverageloop.model.CoverageClassResult;
import com.coverageloop.model.CoverageScope;
import com.coverageloop.model.CoverageStatisticsConfig;
import com.coverageloop.model.CoverageStatisticsGroup;
import com.coverageloop.model.CoverageStatisticsSummary;
import com.coverageloop.model.JavaClassInfo;
import com.coverageloop.model.ScopeKind;
import com.coverageloop.model.ScopeMode;
import com.coverageloop.service.CoverageStatisticsService;
import com.coverageloop.service.RunHistory;
import com.coverageloop.service.ScopeSelection;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 核心逻辑单元测试 */
class CoreLogicTest {

    @Test void dependencyFailureStopsBeforeAnAgentCanTreatUnmeasuredClassesAsMissingTests() throws Exception {
        var config=new com.coverageloop.model.ProjectConfig();config.agent.enabled=true;config.agent.runUntilTarget=false;config.selectedModulePaths=List.of("module-a");
        var failure=new com.coverageloop.model.MavenRunResult();failure.round=1;failure.exitCode=1;
        failure.tests.status=com.coverageloop.model.TestExecutionStatus.build_failed;failure.tests.failureKind="dependency-resolution";failure.tests.message="依赖解析失败，本轮覆盖率无效";
        var sink=new com.coverageloop.service.MavenRunner.OutputSink(){public void output(com.coverageloop.model.MavenOutputEvent e){}public void progress(com.coverageloop.model.MavenProgressEvent e){}};
        var maven=new com.coverageloop.service.MavenRunner(new com.coverageloop.service.MavenRunner.RunnerPaths(),sink){
            @Override public com.coverageloop.model.MavenRunResult run(com.coverageloop.model.ProjectConfig c,String session,RunOptions options){return failure;}
        };
        var agent=new com.coverageloop.service.AgentRunner(sink){
            @Override public com.coverageloop.model.AgentProbeResult probe(com.coverageloop.model.ProjectConfig c){var p=new com.coverageloop.model.AgentProbeResult();p.status=com.coverageloop.model.AgentExecutionStatus.passed;return p;}
            @Override public com.coverageloop.model.AgentRoundResult runRound(com.coverageloop.model.ProjectConfig c,com.coverageloop.model.MavenRunResult r,String mode){throw new AssertionError("Dependency failure must not start agent edits");}
        };
        var result=new com.coverageloop.service.CoverageLoopRunner(maven,agent).run(config);
        assertEquals(com.coverageloop.model.CoverageLoopStopReason.maven_failed,result.stopReason);assertEquals(1,result.rounds.size());assertTrue(result.agentRounds.isEmpty());assertTrue(result.message.contains("依赖解析失败"));
    }

    @Test
    void scopeSelectionIncludeExclude() {
        List<CoverageScope> scopes = new ArrayList<>();
        // 空范围 = 全选
        assertTrue(ScopeSelection.isSelectedClass(scopes, "com.sample.Greeter"));
        // 包 include
        scopes.add(new CoverageScope("module-a", ScopeMode.include, ScopeKind.package_, "com.sample.a"));
        assertTrue(ScopeSelection.isSelectedClass(scopes, "com.sample.a.Greeter"));
        assertTrue(ScopeSelection.isSelectedClass(scopes, "com.sample.a.sub.Helper"));
        assertFalse(ScopeSelection.isSelectedClass(scopes, "com.sample.b.Calculator"));
        // 类 exclude 优先
        scopes.add(new CoverageScope("module-a", ScopeMode.exclude, ScopeKind.class_, "com.sample.a.Greeter"));
        assertFalse(ScopeSelection.isSelectedClass(scopes, "com.sample.a.Greeter"));
        assertTrue(ScopeSelection.isSelectedClass(scopes, "com.sample.a.Other"));
        // 类 include 精确匹配（含内部类）
        List<CoverageScope> classScope = List.of(new CoverageScope("m", ScopeMode.include, ScopeKind.class_, "com.x.Outer"));
        assertTrue(ScopeSelection.isSelectedClass(classScope, "com.x.Outer"));
        assertTrue(ScopeSelection.isSelectedClass(classScope, "com.x.Outer$Inner"));
        assertFalse(ScopeSelection.isSelectedClass(classScope, "com.x.OuterImpl"));
    }

    @Test
    void testClassMatchingForCounts() {
        List<CoverageScope> scopes = List.of(
                new CoverageScope("m", ScopeMode.include, ScopeKind.package_, "com.x"));
        List<JavaClassInfo> tests = List.of(
                new JavaClassInfo("ServiceTest", "com.x", "com.x.ServiceTest", ""),
                new JavaClassInfo("ServiceIT", "com.x", "com.x.ServiceIT", ""),
                new JavaClassInfo("TestService", "com.x", "com.x.TestService", ""),
                new JavaClassInfo("OtherTest", "com.x", "com.x.OtherTest", ""));
        int count = 0;
        for (JavaClassInfo test : tests) {
            if (ScopeSelection.isSelectedTestClass(scopes, test)) count += 1;
        }
        assertEquals(4, count);
    }

    @Test
    void coverageGroupsClassification() {
        RunHistory.CoverageBaseline baseline = new RunHistory.CoverageBaseline();
        baseline.createdAt = "2026-01-01T00:00:00Z";
        baseline.classes.add(classResult("m", "com.x", "Passed", 100, 0, 100.0));
        baseline.classes.add(classResult("m", "com.x", "Pending", 10, 90, 10.0));
        baseline.classes.add(classResult("m", "com.x", "Regression", 70, 30, 70.0));

        List<com.coverageloop.model.CoverageModuleResult> current = new ArrayList<>();
        com.coverageloop.model.CoverageModuleResult module = new com.coverageloop.model.CoverageModuleResult();
        module.modulePath = "m";
        module.classes.add(classResult("m", "com.x", "Passed", 100, 0, 100.0));     // 初始已满足
        module.classes.add(classResult("m", "com.x", "Pending", 30, 70, 30.0));     // 仍待补充
        module.classes.add(classResult("m", "com.x", "Regression", 90, 10, 90.0));  // 已补充（80 -> 90）
        current.add(module);

        var groups = RunHistory.buildCoverageGroups(baseline, current, 80);
        assertEquals(1, groups.initialSatisfied.size());
        assertEquals(1, groups.pending.size());
        assertEquals(1, groups.supplemented.size());
        assertEquals("Pending", groups.pending.get(0).className);
        assertEquals("Regression", groups.supplemented.get(0).className);
    }

    @Test
    void statisticsSummaryDeduplicatesAcrossGroups() {
        CoverageStatisticsConfig config = new CoverageStatisticsConfig();
        config.schemaVersion = 2;
        config.rootPomPath = "pom.xml";
        CoverageStatisticsGroup group1 = new CoverageStatisticsGroup();
        group1.id = "g1";
        group1.name = "组 1";
        group1.modulePaths = List.of("m1");
        group1.confirmedAt = "2026-01-01T00:00:00Z";
        CoverageStatisticsGroup group2 = new CoverageStatisticsGroup();
        group2.id = "g2";
        group2.name = "组 2";
        group2.modulePaths = List.of("m1", "m2");
        group2.confirmedAt = "2026-01-01T00:00:00Z";
        config.groups.add(group1);
        config.groups.add(group2);

        List<CoverageClassResult> m1 = new ArrayList<>();
        m1.add(classResult("m1", "com.x", "A", 10, 10, 50.0));
        m1.add(classResult("m1", "com.x", "B", 20, 0, 100.0));
        List<CoverageClassResult> m2 = new ArrayList<>();
        m2.add(classResult("m2", "com.y", "C", 30, 30, 50.0));
        com.coverageloop.model.CoverageModuleResult module1 = new com.coverageloop.model.CoverageModuleResult();
        module1.modulePath = "m1";
        module1.classes = m1;
        com.coverageloop.model.CoverageModuleResult module2 = new com.coverageloop.model.CoverageModuleResult();
        module2.modulePath = "m2";
        module2.classes = m2;

        CoverageStatisticsSummary summary = CoverageStatisticsService
                .buildCoverageStatisticsSummary(config, List.of(module1, module2), "snapshot-1");
        // 组1: 2 类；组2: m1 2 类 + m2 1 类 = 3 类；去重后 3 类
        assertEquals(2, summary.groups.size());
        assertEquals(3, summary.classCount);
        assertEquals(60, summary.coveredLines);
        assertEquals(40, summary.missedLines);
    }

    private static CoverageClassResult classResult(String modulePath, String packageName, String className,
                                                   int covered, int missed, Double lineCoverage) {
        return new CoverageClassResult(modulePath, packageName, className,
                packageName + "." + className, covered, missed, lineCoverage);
    }
}
