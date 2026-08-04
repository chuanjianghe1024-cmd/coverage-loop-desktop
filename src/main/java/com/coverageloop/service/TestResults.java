package com.coverageloop.service;

import com.coverageloop.model.ProjectConfig;
import com.coverageloop.model.TestExecutionResult;
import com.coverageloop.model.TestExecutionStatus;
import com.coverageloop.model.TestModuleResult;
import com.coverageloop.model.TestModuleStatus;
import com.coverageloop.util.Fs;
import com.coverageloop.util.Names;
import com.coverageloop.util.XmlUtil;
import org.w3c.dom.Element;

import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Surefire 测试结果采集，与 Electron 版 test-results.ts 一致 */
public final class TestResults {

    private static final long FRESH_TIMESTAMP_TOLERANCE_MS = 2_000;
    private static final Pattern LOG_SUMMARY_PATTERN = Pattern.compile(
            "Tests run:\\s*(\\d+),\\s*Failures:\\s*(\\d+),\\s*Errors:\\s*(\\d+),\\s*Skipped:\\s*(\\d+)",
            Pattern.CASE_INSENSITIVE);

    private TestResults() {
    }

    private static class SurefireCounts {
        int tests;
        int failures;
        int errors;
        int skipped;
        int suites;
    }

    private static SurefireCounts parseSurefireXml(String content) {
        Element root = XmlUtil.parse(content);
        Element suite = root == null ? null : XmlUtil.first(root, "testsuite");
        if (suite == null && root != null && root.getTagName().equals("testsuite")) suite = root;
        SurefireCounts counts = new SurefireCounts();
        if (suite == null) return counts;
        counts.tests = intAttr(suite, "tests");
        counts.failures = intAttr(suite, "failures");
        counts.errors = intAttr(suite, "errors");
        counts.skipped = intAttr(suite, "skipped");
        counts.suites = 1;
        return counts;
    }

    private static int intAttr(Element element, String name) {
        String value = XmlUtil.attr(element, name);
        if (value.isEmpty()) return 0;
        try {
            return (int) Double.parseDouble(value);
        } catch (NumberFormatException error) {
            return 0;
        }
    }

    private static String safeModuleDirectory(String modulePath) {
        return ".".equals(modulePath) ? "root" : modulePath.replaceAll("[\\\\/:*?\"<>|]+", "__");
    }

    private static boolean isSurefireArtifact(String name) {
        return name.matches("(?i)^TEST-.+\\.xml$") || name.matches(".*\\.(txt|dump|dumpstream)$");
    }

    private static TestModuleResult readModuleResult(String projectRoot, String modulePath,
                                                     long startedAtMs, String archiveRoot) {
        String moduleDirectory = ".".equals(modulePath) ? projectRoot : new File(projectRoot, modulePath).getPath();
        String reportDirectory = new File(new File(moduleDirectory, "target"), "surefire-reports").getPath();
        List<File> artifacts = new ArrayList<>();
        File reportDir = new File(reportDirectory);
        if (reportDir.isDirectory()) {
            File[] entries = reportDir.listFiles();
            if (entries != null) {
                for (File entry : entries) {
                    if (!entry.isFile() || !isSurefireArtifact(entry.getName())) continue;
                    if (Fs.lastModifiedMs(entry.getPath()) + FRESH_TIMESTAMP_TOLERANCE_MS >= startedAtMs) {
                        artifacts.add(entry);
                    }
                }
            }
        }

        SurefireCounts total = new SurefireCounts();
        for (File artifact : artifacts) {
            if (!artifact.getName().matches("(?i)^TEST-.+\\.xml$")) continue;
            try {
                SurefireCounts counts = parseSurefireXml(Fs.readString(artifact.getPath()));
                total.tests += counts.tests;
                total.failures += counts.failures;
                total.errors += counts.errors;
                total.skipped += counts.skipped;
                total.suites += counts.suites;
            } catch (Exception error) {
                // 原始报告仍会归档，单个损坏 XML 不影响其他报告统计
            }
        }

        if (!artifacts.isEmpty()) {
            String destination = new File(archiveRoot, safeModuleDirectory(modulePath)).getPath();
            Fs.mkdirs(destination);
            for (File artifact : artifacts) {
                Fs.copy(artifact.getPath(), new File(destination, artifact.getName()).getPath());
            }
        }

        TestModuleResult result = new TestModuleResult();
        result.modulePath = modulePath;
        result.tests = total.tests;
        result.failures = total.failures;
        result.errors = total.errors;
        result.skipped = total.skipped;
        result.suites = total.suites;
        result.reportDirectory = reportDirectory;
        result.freshReportCount = artifacts.size();
        result.status = total.failures + total.errors > 0 ? TestModuleStatus.failed
                : total.tests > 0 ? TestModuleStatus.passed
                : !artifacts.isEmpty() ? TestModuleStatus.no_tests : TestModuleStatus.unknown;
        return result;
    }

    private static class LogCounts {
        Integer tests;
        Integer failures;
        Integer errors;
        Integer skipped;

        boolean hasValue() {
            return tests != null;
        }
    }

    private static LogCounts logSummary(String logText) {
        LogCounts result = new LogCounts();
        Matcher matcher = LOG_SUMMARY_PATTERN.matcher(logText);
        while (matcher.find()) {
            result.tests = Integer.parseInt(matcher.group(1));
            result.failures = Integer.parseInt(matcher.group(2));
            result.errors = Integer.parseInt(matcher.group(3));
            result.skipped = Integer.parseInt(matcher.group(4));
        }
        return result;
    }

    private static TestExecutionStatus classifyStatus(Integer exitCode, String signal,
                                                      int tests, int failures, int errors, String logText) {
        if (signal != null || exitCode == null) return TestExecutionStatus.aborted;
        if (failures + errors > 0 || logText.toLowerCase().contains("there are test failures")) {
            return TestExecutionStatus.test_failed;
        }
        if (exitCode != 0) return TestExecutionStatus.build_failed;
        return tests > 0 ? TestExecutionStatus.passed : TestExecutionStatus.no_tests;
    }

    private static String statusMessage(TestExecutionStatus status, int noTestModules, boolean continuedAfterFailure) {
        String suffix = noTestModules > 0 ? "；" + noTestModules + " 个所选模块没有本轮测试报告" : "";
        switch (status) {
            case passed:
                return "测试执行通过" + suffix;
            case no_tests:
                return "没有发现可执行测试；本轮继续生成 0% 覆盖率基线";
            case test_failed:
                return continuedAfterFailure
                        ? "存在失败测试；统计模式已继续执行后续模块，Surefire 报告和 Maven 日志已保存"
                        : "测试执行失败；Surefire 报告和 Maven 日志已保存";
            case build_failed:
                return "Maven 在测试结果完成前失败；请查看完整日志和已归档报告";
            default:
                return "Maven 进程被终止；已保存当前日志和报告";
        }
    }

    public static class CollectOptions {
        public ProjectConfig config;
        public RunHistory.PreparedRound prepared;
        public String startedAt;
        public Integer exitCode;
        public String signal;
        public String logText;
        public boolean continueOnTestFailure;
    }

    public static TestExecutionResult collectTestExecutionResult(CollectOptions options) {
        String projectRoot = new File(options.config.rootPomPath).getAbsoluteFile().getParent();
        String roundLabel = Names.roundLabel(options.prepared.round);
        String archiveRoot = new File(options.prepared.runDirectory, "round-" + roundLabel + "-surefire").getPath();
        long startedAtMs = Instant.parse(options.startedAt).toEpochMilli();

        List<TestModuleResult> modules = new ArrayList<>();
        for (String modulePath : options.config.selectedModulePaths) {
            modules.add(readModuleResult(projectRoot, modulePath, startedAtMs, archiveRoot));
        }

        int freshArtifacts = 0;
        SurefireCounts xmlSummary = new SurefireCounts();
        boolean hasParsedXml = false;
        for (TestModuleResult module : modules) {
            freshArtifacts += module.freshReportCount;
            xmlSummary.tests += module.tests;
            xmlSummary.failures += module.failures;
            xmlSummary.errors += module.errors;
            xmlSummary.skipped += module.skipped;
            if (module.suites > 0) hasParsedXml = true;
        }

        LogCounts fallback = hasParsedXml ? null : logSummary(options.logText);
        int tests = fallback != null ? fallback.tests : xmlSummary.tests;
        int failures = fallback != null ? fallback.failures : xmlSummary.failures;
        int errors = fallback != null ? fallback.errors : xmlSummary.errors;
        int skipped = fallback != null ? fallback.skipped : xmlSummary.skipped;

        TestExecutionStatus status = classifyStatus(options.exitCode, options.signal,
                tests, failures, errors, options.logText);
        boolean continuedAfterFailure = status == TestExecutionStatus.test_failed
                && options.continueOnTestFailure
                && options.exitCode != null && options.exitCode == 0;

        for (TestModuleResult module : modules) {
            if (module.status == TestModuleStatus.unknown && options.exitCode != null && options.exitCode == 0) {
                module.status = TestModuleStatus.no_tests;
            }
        }
        int noTestModules = 0;
        for (TestModuleResult module : modules) {
            if (module.status == TestModuleStatus.no_tests || module.status == TestModuleStatus.unknown) {
                noTestModules += 1;
            }
        }

        TestExecutionResult result = new TestExecutionResult();
        result.status = status;
        result.continuedAfterFailure = continuedAfterFailure;
        result.tests = tests;
        result.failures = failures;
        result.errors = errors;
        result.skipped = skipped;
        result.modules = modules;
        result.reportArchivePath = freshArtifacts > 0 ? archiveRoot : null;
        result.message = statusMessage(status, noTestModules, continuedAfterFailure);
        return result;
    }
}
