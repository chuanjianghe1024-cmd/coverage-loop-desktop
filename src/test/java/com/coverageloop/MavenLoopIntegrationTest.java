package com.coverageloop;

import com.coverageloop.model.AgentOptions;
import com.coverageloop.model.CoverageModuleResult;
import com.coverageloop.model.MavenOutputEvent;
import com.coverageloop.model.MavenProgressEvent;
import com.coverageloop.model.MavenRunResult;
import com.coverageloop.model.ProjectConfig;
import com.coverageloop.model.ProjectScanResult;
import com.coverageloop.model.TestExecutionStatus;
import com.coverageloop.service.ConfigStore;
import com.coverageloop.service.CoverageReader;
import com.coverageloop.service.MavenRunner;
import com.coverageloop.service.ProjectScanner;
import com.coverageloop.util.Fs;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 端到端集成测试：真实 Maven + JaCoCo 运行样例项目 */
class MavenLoopIntegrationTest {

    private static String projectRoot;
    private static String rootPom;

    @BeforeAll
    static void prepareSampleProject() throws Exception {
        Path source = Path.of("src/test/resources/sample-project").toAbsolutePath();
        Path target = Files.createTempDirectory("coverage-loop-sample");
        copyTree(source, target);
        projectRoot = target.toString();
        rootPom = new File(target.toFile(), "pom.xml").getPath();
        // 确保样例项目先用 Maven 安装自身依赖（junit）
        // 这里不预跑 install，第一轮 preInstall 会做
    }

    private static void copyTree(Path source, Path target) throws Exception {
        try (var stream = Files.walk(source)) {
            for (Path item : stream.toList()) {
                Path destination = target.resolve(source.relativize(item).toString());
                if (Files.isDirectory(item)) {
                    Files.createDirectories(destination);
                } else {
                    Files.copy(item, destination);
                }
            }
        }
    }

    private ProjectConfig sampleConfig() {
        ProjectConfig config = com.coverageloop.model.ConfigFactory.createDefaultConfig(rootPom);
        config.id = "test-config";
        config.name = "测试配置";
        config.selectedModulePaths = List.of("module-a", "module-b");
        config.maven.useBundledMaven = true;
        config.maven.preInstall = true;
        config.maven.parallelThreads = 1;
        config.coverage.jacocoVersion = "0.8.8";
        config.coverage.lineThreshold = 50;
        AgentOptions agent = new AgentOptions();
        agent.enabled = false;
        config.agent = agent;
        return config;
    }

    private MavenRunner newRunner() {
        MavenRunner.OutputSink sink = new MavenRunner.OutputSink() {
            @Override
            public void output(MavenOutputEvent event) {
                // 静默
            }

            @Override
            public void progress(MavenProgressEvent event) {
                // 静默
            }
        };
        MavenRunner.RunnerPaths paths = new MavenRunner.RunnerPaths();
        paths.resourcesPath = new File(System.getProperty("user.dir"), "resources").getPath();
        paths.developmentRoot = System.getProperty("user.dir");
        return new MavenRunner(paths, sink);
    }

    @Test
    void scanFindsModules() {
        ProjectScanResult scan = ProjectScanner.scanMavenProject(rootPom);
        assertEquals(2, scan.modules.size());
        assertTrue(scan.modules.stream().anyMatch(m -> m.relativePath.equals("module-a")));
        assertTrue(scan.modules.stream().anyMatch(m -> m.relativePath.equals("module-b")));
        for (var module : scan.modules) {
            assertTrue(module.hasMainSources);
            assertTrue(module.hasTests);
        }
        // 源码扫描
        var sources = ProjectScanner.scanModuleSources(scan.modules.get(0));
        assertEquals("com.sample.a", sources.classes.get(0).packageName);
        assertTrue(sources.sourceFileCount >= 1);
        assertTrue(sources.testFileCount >= 1);
    }

    @Test
    void fullMavenRoundGeneratesCoverageAndGroups() throws Exception {
        ProjectConfig config = sampleConfig();
        MavenRunner runner = newRunner();
        MavenRunResult result = runner.run(config, null, new MavenRunner.RunOptions());
        assertEquals(0, result.exitCode, "Maven 应成功退出");
        assertEquals(TestExecutionStatus.passed, result.tests.status);
        assertEquals(2, result.coverage.size());
        for (CoverageModuleResult module : result.coverage) {
            assertEquals("jacoco", module.source);
            assertTrue(module.classCount >= 1, module.modulePath + " 应有至少一个类");
            assertTrue(module.lineCoverage > 0, module.modulePath + " 覆盖率应大于 0");
        }
        // 分组总和 = 类总数
        int totalClasses = result.groups.initialSatisfied.size()
                + result.groups.pending.size() + result.groups.supplemented.size();
        int coveredClasses = result.coverage.stream().mapToInt(m -> m.classCount).sum();
        assertEquals(coveredClasses, totalClasses);
        // 工件文件
        assertTrue(Fs.exists(new File(result.runDirectory, "round-001-maven.log").getPath()));
        assertTrue(Fs.exists(result.coverageSnapshotPath));
        assertTrue(Fs.exists(new File(result.runDirectory, "round-001-coverage-gate.txt").getPath()));
        assertTrue(Fs.exists(new File(result.runDirectory, "round-001-failed-classes.txt").getPath()));
        assertTrue(Fs.exists(new File(result.runDirectory, "baseline-coverage.json").getPath()));
        assertTrue(Fs.exists(new File(result.runDirectory, "session.json").getPath()));
        assertTrue(Fs.exists(new File(result.runDirectory, "round-001-test-summary.txt").getPath()));

        // 第二轮：沿用同一执行 ID
        MavenRunResult round2 = runner.run(config, result.runId, new MavenRunner.RunOptions());
        assertEquals(2, round2.round);
        assertTrue(Fs.exists(new File(round2.runDirectory, "round-002-maven.log").getPath()));
    }

    @Test
    void sourceFallbackWhenNoReport() {
        ProjectConfig config = sampleConfig();
        // 删除 jacoco 报告模拟无报告场景
        for (String modulePath : List.of("module-a", "module-b")) {
            File report = new File(new File(new File(projectRoot, modulePath), "target/site/jacoco"), "jacoco.xml");
            if (report.exists()) report.delete();
        }
        List<CoverageModuleResult> coverage = CoverageReader.readCoverageResults(config, null);
        for (CoverageModuleResult module : coverage) {
            assertEquals("source-fallback", module.source);
            assertEquals(0.0, module.lineCoverage);
            assertTrue(module.classCount >= 1);
        }
    }

    @Test
    void configStoreRoundTrip() {
        ProjectConfig config = sampleConfig();
        String path = ConfigStore.saveProjectConfig(config);
        assertTrue(Fs.exists(path));
        ProjectConfig loaded = ConfigStore.loadProjectConfig(rootPom, "test-config");
        assertNotNull(loaded);
        assertEquals("测试配置", loaded.name);
        assertEquals(2, loaded.selectedModulePaths.size());
        assertEquals("0.8.8", loaded.coverage.jacocoVersion);
        assertEquals(50, loaded.coverage.lineThreshold);
        // 列表与删除
        var summaries = ConfigStore.listProjectConfigs(rootPom);
        assertTrue(summaries.stream().anyMatch(s -> s.id.equals("test-config")));
        assertTrue(ConfigStore.deleteProjectConfig(rootPom, "test-config"));
    }
}
