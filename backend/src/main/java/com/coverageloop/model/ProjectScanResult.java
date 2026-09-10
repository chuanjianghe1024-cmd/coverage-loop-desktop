package com.coverageloop.model;

import java.util.ArrayList;
import java.util.List;

/** 项目扫描结果 */
public class ProjectScanResult {
    public String rootPomPath;
    public String rootDirectory;
    public String rootArtifactId;
    public List<MavenModule> modules = new ArrayList<>();
    public List<String> warnings = new ArrayList<>();
}
