package com.coverageloop.model;

import com.coverageloop.service.PromptTemplates;

import java.time.Instant;

/** 默认配置工厂，与 Electron 版 createDefaultConfig / createDefaultCoverageStatistics 一致 */
public final class ConfigFactory {

    private ConfigFactory() {
    }

    public static ProjectConfig createDefaultConfig(String rootPomPath) {
        return createDefaultConfig(rootPomPath, "default", "默认配置");
    }

    public static ProjectConfig createDefaultConfig(String rootPomPath, String id, String name) {
        String now = Instant.now().toString();
        ProjectConfig config = new ProjectConfig();
        config.schemaVersion = 5;
        config.id = id;
        config.name = name;
        config.rootPomPath = rootPomPath;
        config.maven.useBundledMaven = true;
        config.maven.preInstall = true;
        config.maven.parallelThreads = 1;
        config.maven.localRepository = com.coverageloop.util.Proc.isWindows() ? "D:/m2" : "";
        config.coverage.jacocoVersion = "0.8.8";
        config.coverage.lineThreshold = 80;
        config.coverage.branchThreshold = 0;
        config.agent.enabled = false;
        config.agent.provider = "hermes";
        config.agent.maxRounds = 5;
        config.agent.batchSize = 3;
        config.agent.timeoutMinutes = 30;
        config.agent.autoApprove = true;
        config.agent.maxSameFailures = 3;
        config.agent.heartbeatSeconds = 10;
        config.agent.coveragePromptTemplate = PromptTemplates.DEFAULT_COVERAGE_PROMPT_TEMPLATE;
        config.agent.repairPromptTemplate = PromptTemplates.DEFAULT_REPAIR_PROMPT_TEMPLATE;
        config.createdAt = now;
        config.updatedAt = now;
        return config;
    }

    public static CoverageStatisticsConfig createDefaultCoverageStatistics(String rootPomPath) {
        CoverageStatisticsConfig config = new CoverageStatisticsConfig();
        config.schemaVersion = 2;
        config.rootPomPath = rootPomPath;
        CoverageStatisticsGroup group = new CoverageStatisticsGroup();
        group.id = "group-1";
        group.name = "分组 1";
        config.groups.add(group);
        config.updatedAt = Instant.now().toString();
        return config;
    }
}
