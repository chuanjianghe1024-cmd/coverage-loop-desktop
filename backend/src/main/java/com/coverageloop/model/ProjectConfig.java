package com.coverageloop.model;

import java.util.ArrayList;
import java.util.List;

/** 项目配置（与 Electron 版 .coverage-loop/configs/*.json 完全兼容） */
public class ProjectConfig {
    public int schemaVersion = 5;
    public String id;
    public String name;
    public String rootPomPath;
    public List<String> selectedModulePaths = new ArrayList<>();
    public List<CoverageScope> scopes = new ArrayList<>();
    public MavenOptions maven = new MavenOptions();
    public CoverageOptions coverage = new CoverageOptions();
    public AgentOptions agent = new AgentOptions();
    public String createdAt;
    public String updatedAt;

    public ProjectConfig copy() {
        ProjectConfig copy = new ProjectConfig();
        copy.schemaVersion = schemaVersion;
        copy.id = id;
        copy.name = name;
        copy.rootPomPath = rootPomPath;
        copy.selectedModulePaths = new ArrayList<>(selectedModulePaths);
        copy.scopes = new ArrayList<>();
        for (CoverageScope scope : scopes) copy.scopes.add(scope.copy());
        copy.maven = maven.copy();
        copy.coverage = coverage.copy();
        copy.agent = agent.copy();
        copy.createdAt = createdAt;
        copy.updatedAt = updatedAt;
        return copy;
    }
}
