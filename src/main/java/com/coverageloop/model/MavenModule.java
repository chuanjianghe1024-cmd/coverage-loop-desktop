package com.coverageloop.model;

/** Maven 多模块项目中的一个模块 */
public class MavenModule {
    public String name;
    public String artifactId;
    /** 相对根目录的路径，根模块为 "." */
    public String relativePath;
    public String pomPath;
    public String packaging;
    public String sourceRoot;
    public String testRoot;
    public boolean hasMainSources;
    public boolean hasTests;

    public MavenModule() {
    }

    public MavenModule(String name, String artifactId, String relativePath, String pomPath, String packaging,
                       String sourceRoot, String testRoot, boolean hasMainSources, boolean hasTests) {
        this.name = name;
        this.artifactId = artifactId;
        this.relativePath = relativePath;
        this.pomPath = pomPath;
        this.packaging = packaging;
        this.sourceRoot = sourceRoot;
        this.testRoot = testRoot;
        this.hasMainSources = hasMainSources;
        this.hasTests = hasTests;
    }

    /** 界面展示名：根模块直接显示 artifactId，子模块显示 artifactId · 相对路径 */
    public String label() {
        return ".".equals(relativePath) ? artifactId : artifactId + " · " + relativePath;
    }
}
