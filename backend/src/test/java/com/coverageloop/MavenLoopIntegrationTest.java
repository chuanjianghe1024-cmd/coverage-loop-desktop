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
import com.coverageloop.service.CoverageStatisticsService;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        config.maven.executable = System.getProperty("coverage.test.maven", "");
        config.maven.settingsPath = System.getProperty("coverage.test.settings", "");
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

    @Test
    void freshProjectWithoutCoverageLoopDirLoadsCleanly() throws Exception {
        // 全新项目：.coverage-loop 目录不存在时，加载配置/统计不得抛 NPE
        Path freshRoot = Files.createTempDirectory("coverage-loop-fresh");
        String freshPom = new File(freshRoot.toFile(), "pom.xml").getPath();
        Fs.writeString(freshPom, "<?xml version=\"1.0\"?><project><modelVersion>4.0.0</modelVersion>"
                + "<groupId>t</groupId><artifactId>t</artifactId><version>1</version></project>");
        // 无配置时返回 null（初始化流程会用默认配置兜底），且不得抛 NPE
        assertNull(ConfigStore.loadProjectConfig(freshPom, null));
        assertTrue(ConfigStore.listProjectConfigs(freshPom).isEmpty());
        ProjectConfig fallback = com.coverageloop.model.ConfigFactory.createDefaultConfig(freshPom);
        assertNotNull(fallback);
        assertEquals("default", fallback.id);
        var statistics = CoverageStatisticsService.loadCoverageStatisticsState(freshPom);
        assertNotNull(statistics.config);
        assertEquals("group-1", statistics.config.groups.get(0).id);
        // 统计执行模块（无模块）应为空且不抛异常
        var scan = ProjectScanner.scanMavenProject(freshPom);
        assertTrue(CoverageStatisticsService
                .coverageStatisticsExecutionModulePaths(scan, statistics.config).isEmpty());
    }

    @Test
    void singleModuleProjectScansRootAsModule() throws Exception {
        Path single = Files.createTempDirectory("coverage-loop-single");
        writeSingleModuleProject(single);
        String pom = new File(single.toFile(), "pom.xml").getPath();
        ProjectScanResult scan = ProjectScanner.scanMavenProject(pom);
        assertEquals(1, scan.modules.size(), "单模块项目应扫描出根模块");
        var root = scan.modules.get(0);
        assertEquals(".", root.relativePath);
        assertTrue(root.hasMainSources);
        assertTrue(root.hasTests);
        // 源码扫描
        var sources = ProjectScanner.scanModuleSources(root);
        assertTrue(sources.sourceFileCount >= 1);
        assertTrue(sources.testFileCount >= 1);
    }

    @Test
    void singleModuleMavenRoundRunsWithoutPl() throws Exception {
        Path single = Files.createTempDirectory("coverage-loop-single-run");
        writeSingleModuleProject(single);
        String pom = new File(single.toFile(), "pom.xml").getPath();
        ProjectConfig config = com.coverageloop.model.ConfigFactory.createDefaultConfig(pom);
        config.id = "single-test";
        config.selectedModulePaths = List.of(".");
        config.maven.useBundledMaven = true;
        config.maven.executable = System.getProperty("coverage.test.maven", "");
        config.maven.settingsPath = System.getProperty("coverage.test.settings", "");
        config.maven.preInstall = false;
        config.coverage.jacocoVersion = "0.8.8";
        config.coverage.lineThreshold = 50;

        MavenRunner runner = newRunner();
        MavenRunResult result = runner.run(config, null, new MavenRunner.RunOptions());
        assertEquals(0, result.exitCode, "单模块 Maven 应成功");
        assertFalse(result.command.args.contains("-pl"), "单模块项目不应出现 -pl 参数");
        assertEquals(1, result.coverage.size());
        assertEquals(".", result.coverage.get(0).modulePath);
        assertEquals("jacoco", result.coverage.get(0).source);
        assertTrue(result.coverage.get(0).classCount >= 1);
        assertTrue(result.coverage.get(0).lineCoverage > 0);
    }

    private static void writeSingleModuleProject(Path root) throws Exception {
        String pom = "<?xml version=\"1.0\"?><project><modelVersion>4.0.0</modelVersion>"
                + "<groupId>com.single</groupId><artifactId>single-app</artifactId><version>1.0.0</version>"
                + "<properties><maven.compiler.release>17</maven.compiler.release>"
                + "<project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties>"
                + "<dependencies><dependency><groupId>org.junit.jupiter</groupId>"
                + "<artifactId>junit-jupiter</artifactId><version>5.10.2</version><scope>test</scope>"
                + "</dependency></dependencies>"
                + "<build><plugins><plugin><groupId>org.apache.maven.plugins</groupId>"
                + "<artifactId>maven-surefire-plugin</artifactId><version>3.2.5</version>"
                + "</plugin></plugins></build></project>";
        Fs.writeString(new File(root.toFile(), "pom.xml").getPath(), pom);
        Fs.writeString(new File(root.toFile(), "src/main/java/com/single/App.java").getPath(),
                "package com.single;\npublic class App {\n"
                        + "    public String hello(String name) {\n"
                        + "        return name == null || name.isBlank() ? \"world\" : name;\n"
                        + "    }\n"
                        + "    public int add(int a, int b) {\n"
                        + "        return a + b;\n"
                        + "    }\n"
                        + "}\n");
        Fs.writeString(new File(root.toFile(), "src/test/java/com/single/AppTest.java").getPath(),
                "package com.single;\nimport org.junit.jupiter.api.Test;\n"
                        + "import static org.junit.jupiter.api.Assertions.assertEquals;\n"
                        + "class AppTest {\n"
                        + "    @Test\n    void works() {\n"
                        + "        App app = new App();\n"
                        + "        assertEquals(\"world\", app.hello(\"\"));\n"
                        + "        assertEquals(3, app.add(1, 2));\n"
                        + "    }\n"
                        + "}\n");
    }
}
