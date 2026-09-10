package com.coverageloop.service;

import com.coverageloop.model.AgentOptions;
import com.coverageloop.model.ConfigFactory;
import com.coverageloop.model.ConfigSummary;
import com.coverageloop.model.CoverageOptions;
import com.coverageloop.model.CoverageScope;
import com.coverageloop.model.MavenOptions;
import com.coverageloop.model.ProjectConfig;
import com.coverageloop.model.ScopeKind;
import com.coverageloop.model.ScopeMode;
import com.coverageloop.util.Fs;
import com.coverageloop.util.Json;
import com.coverageloop.util.Names;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 项目配置存储，与 Electron 版 config-store.ts 一致（.coverage-loop/configs/*.json） */
public final class ConfigStore {

    private ConfigStore() {
    }

    public static String coverageRootForPom(String rootPomPath) {
        return new File(new File(rootPomPath).getAbsoluteFile().getParent(), ".coverage-loop").getPath();
    }

    public static String configsDirectoryForPom(String rootPomPath) {
        return new File(coverageRootForPom(rootPomPath), "configs").getPath();
    }

    public static String legacyConfigPathForPom(String rootPomPath) {
        return new File(coverageRootForPom(rootPomPath), "project.json").getPath();
    }

    public static String configPathForPom(String rootPomPath, String configId) {
        return new File(configsDirectoryForPom(rootPomPath), Names.normalizedIdOrThrow(configId, "配置 ID") + ".json").getPath();
    }

    /** 解析配置：兼容 schemaVersion 1-5，缺失字段用默认值补齐 */
    public static ProjectConfig parseConfig(String content, String rootPomPath) {
        ProjectConfig parsed = Json.fromJson(content, ProjectConfig.class);
        if (parsed == null || parsed.schemaVersion < 1 || parsed.schemaVersion > 5) {
            throw new IllegalArgumentException("暂不支持配置版本：" + (parsed == null ? "null" : parsed.schemaVersion));
        }
        ProjectConfig fallback = ConfigFactory.createDefaultConfig(rootPomPath);
        boolean legacy = parsed.schemaVersion == 1;
        ProjectConfig result = fallback.copy();
        result.schemaVersion = 5;
        result.id = legacy ? "default" : firstNonEmpty(parsed.id, fallback.id);
        result.name = legacy ? "默认配置（由旧版迁移）" : firstNonEmpty(parsed.name, fallback.name);
        result.rootPomPath = rootPomPath;
        result.selectedModulePaths = parsed.selectedModulePaths == null
                ? new ArrayList<>() : new ArrayList<>(parsed.selectedModulePaths);
        result.scopes = parsed.scopes == null ? new ArrayList<>() : sanitizeScopes(parsed.scopes);
        mergeMaven(result.maven, parsed.maven);
        mergeCoverage(result.coverage, parsed.coverage);
        mergeAgent(result.agent, parsed.agent);
        result.createdAt = firstNonEmpty(parsed.createdAt, firstNonEmpty(parsed.updatedAt, fallback.createdAt));
        result.updatedAt = firstNonEmpty(parsed.updatedAt, fallback.updatedAt);
        return result;
    }

    private static List<CoverageScope> sanitizeScopes(List<CoverageScope> scopes) {
        List<CoverageScope> result = new ArrayList<>();
        for (CoverageScope scope : scopes) {
            if (scope == null || scope.modulePath == null || scope.pattern == null) continue;
            if (scope.mode == null) scope.mode = ScopeMode.include;
            if (scope.kind == null) scope.kind = ScopeKind.package_;
            result.add(scope);
        }
        return result;
    }

    private static void mergeMaven(MavenOptions target, MavenOptions source) {
        if (source == null) return;
        target.useBundledMaven = source.useBundledMaven;
        target.preInstall = source.preInstall;
        target.parallelThreads = source.parallelThreads;
        target.executable = source.executable == null ? "" : source.executable;
        target.javaHome = source.javaHome == null ? "" : source.javaHome;
        target.settingsPath = source.settingsPath == null ? "" : source.settingsPath;
        target.localRepository = source.localRepository == null ? "" : source.localRepository;
        target.profiles = source.profiles == null ? new ArrayList<>() : new ArrayList<>(source.profiles);
        target.extraArgs = source.extraArgs == null ? new ArrayList<>() : new ArrayList<>(source.extraArgs);
        target.versionNumber = source.versionNumber == null ? "" : source.versionNumber.trim();
        target.forceUpdate = source.forceUpdate;
        // Preserve old extra-argument configurations while moving common options into visible fields.
        boolean explicitVersion = !target.versionNumber.isEmpty();
        for (String arg : target.extraArgs) {
            if (arg == null) continue;
            String value = arg.trim();
            if (!explicitVersion && value.startsWith("-Dversion_number=")) target.versionNumber=value.substring(17).trim();
            if (value.equals("-U") || value.equals("--update-snapshots")) target.forceUpdate=true;
        }
        target.extraArgs.removeIf(arg -> arg != null && (arg.trim().startsWith("-Dversion_number=") || arg.trim().equals("-U") || arg.trim().equals("--update-snapshots")));
        target.testPattern = source.testPattern == null ? "" : source.testPattern;
    }

    private static void mergeCoverage(CoverageOptions target, CoverageOptions source) {
        if (source == null) return;
        target.jacocoVersion = source.jacocoVersion == null ? "0.8.8" : source.jacocoVersion;
        target.lineThreshold = source.lineThreshold;
        target.branchThreshold = source.branchThreshold;
    }

    private static void mergeAgent(AgentOptions target, AgentOptions source) {
        if (source == null) return;
        target.enabled = source.enabled;
        target.provider = source.provider == null ? "hermes" : source.provider;
        target.model = source.model == null ? "" : source.model;
        target.maxRounds = source.maxRounds;
        target.executable = source.executable == null ? "" : source.executable;
        target.extraArgs = source.extraArgs == null ? new ArrayList<>() : new ArrayList<>(source.extraArgs);
        target.batchSize = source.batchSize;
        target.timeoutMinutes = source.timeoutMinutes;
        target.autoApprove = source.autoApprove;
        target.maxSameFailures = source.maxSameFailures;
        target.heartbeatSeconds = source.heartbeatSeconds;
        target.allowProductionChanges = source.allowProductionChanges;
        target.hermesProvider = source.hermesProvider == null ? "" : source.hermesProvider;
        target.opencodeAgent = source.opencodeAgent == null ? "" : source.opencodeAgent;
        target.opencodeAttach = source.opencodeAttach == null ? "" : source.opencodeAttach;
        target.coveragePromptTemplate = source.coveragePromptTemplate == null ? "" : source.coveragePromptTemplate;
        target.repairPromptTemplate = source.repairPromptTemplate == null ? "" : source.repairPromptTemplate;
    }

    private static String firstNonEmpty(String first, String fallback) {
        return first == null || first.isBlank() ? fallback : first;
    }

    public static String saveProjectConfig(ProjectConfig config) {
        String id = Names.normalizedIdOrThrow(config.id, "配置 ID");
        String path = configPathForPom(config.rootPomPath, id);
        AgentOptions defaultAgent = ConfigFactory.createDefaultConfig(config.rootPomPath).agent;
        ProjectConfig value = config.copy();
        value.schemaVersion = 5;
        value.id = id;
        value.name = config.name == null || config.name.trim().isEmpty() ? "未命名配置" : config.name.trim();
        value.maven.parallelThreads = clamp(config.maven.parallelThreads, 1, 32, 1);
        value.agent.maxRounds = clamp(config.agent.maxRounds, 1, 100, 1);
        value.agent.batchSize = clamp(config.agent.batchSize, 1, 200, 1);
        value.agent.timeoutMinutes = clamp(config.agent.timeoutMinutes, 1, 240, 1);
        value.agent.maxSameFailures = clamp(config.agent.maxSameFailures, 1, 20, 1);
        value.agent.heartbeatSeconds = clamp(config.agent.heartbeatSeconds, 1, 300, 1);
        value.agent.coveragePromptTemplate = config.agent.coveragePromptTemplate == null
                || config.agent.coveragePromptTemplate.trim().isEmpty()
                ? defaultAgent.coveragePromptTemplate : config.agent.coveragePromptTemplate.trim();
        value.agent.repairPromptTemplate = config.agent.repairPromptTemplate == null
                || config.agent.repairPromptTemplate.trim().isEmpty()
                ? defaultAgent.repairPromptTemplate : config.agent.repairPromptTemplate.trim();
        value.updatedAt = Names.nowIso();
        Fs.writeString(path, Json.toJson(value) + "\n");
        return path;
    }

    private static int clamp(int value, int min, int max, int fallback) {
        if (value < min || value > max) {
            int base = value == 0 ? fallback : value;
            return Math.max(min, Math.min(max, base));
        }
        return value;
    }

    private static List<ProjectConfig> readAllConfigs(String rootPomPath) {
        List<ProjectConfig> configs = new ArrayList<>();
        String directory = configsDirectoryForPom(rootPomPath);
        File[] files = new File(directory).listFiles();
        if (files != null) {
            for (File entry : files) {
                if (!entry.isFile() || !entry.getName().endsWith(".json")) continue;
                try {
                    configs.add(parseConfig(Fs.readString(entry.getPath()), rootPomPath));
                } catch (Exception error) {
                    // 跳过损坏的配置文件
                }
            }
        }
        if (configs.isEmpty()) {
            String legacyPath = legacyConfigPathForPom(rootPomPath);
            if (Fs.exists(legacyPath)) {
                try {
                    configs.add(parseConfig(Fs.readString(legacyPath), rootPomPath));
                } catch (Exception error) {
                    // 旧版文件损坏时忽略
                }
            }
        }
        configs.sort(Comparator.comparing((ProjectConfig config) -> config.updatedAt).reversed());
        return configs;
    }

    public static List<ConfigSummary> listProjectConfigs(String rootPomPath) {
        List<ConfigSummary> summaries = new ArrayList<>();
        for (ProjectConfig config : readAllConfigs(rootPomPath)) {
            ConfigSummary summary = new ConfigSummary();
            summary.id = config.id;
            summary.name = config.name;
            summary.updatedAt = config.updatedAt;
            summary.selectedModuleCount = config.selectedModulePaths.size();
            summaries.add(summary);
        }
        return summaries;
    }

    public static ProjectConfig loadProjectConfig(String rootPomPath, String configId) {
        List<ProjectConfig> configs = readAllConfigs(rootPomPath);
        if (configs.isEmpty()) return null;
        if (configId == null || configId.isEmpty()) return configs.get(0);
        String id = Names.normalizedId(configId, "");
        return configs.stream().filter(config -> config.id.equals(id)).findFirst().orElse(null);
    }

    public static boolean deleteProjectConfig(String rootPomPath, String configId) {
        String path = configPathForPom(rootPomPath, configId);
        if (!Fs.exists(path)) return false;
        Fs.delete(path);
        return true;
    }
}
