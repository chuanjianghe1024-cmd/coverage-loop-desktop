package com.coverageloop.service;

import com.coverageloop.model.*;
import java.util.*;

/** Add line counters, never average percentages. Missing reports stay unmeasured. */
public final class StatisticsTree {
    private StatisticsTree() {}
    public static final class Node {
        public String id, name, kind;
        public int coveredLines, totalLines, classCount;
        public Double lineCoverage;
        public boolean measured = true;
        public List<Node> children = new ArrayList<>();
        Node(String id, String name, String kind) { this.id=id; this.name=name; this.kind=kind; }
        void aggregate() {
            coveredLines=0; totalLines=0; classCount=0;
            for (Node child:children) { coveredLines+=child.coveredLines; totalLines+=child.totalLines; classCount+=child.classCount; measured &= child.measured; }
            lineCoverage=measured && totalLines>0 ? coveredLines*100.0/totalLines : null;
        }
    }
    public static Node build(ProjectConfig config, List<CoverageModuleResult> coverage) {
        ProjectScanResult project = ProjectScanner.scanMavenProject(config.rootPomPath);
        Node root = new Node("project", project.rootArtifactId, "project");
        Map<String,Node> modules = new LinkedHashMap<>();
        for (MavenModule m:project.modules) {
            boolean used=config.selectedModulePaths.stream().anyMatch(p -> p.equals(m.relativePath) || p.startsWith(m.relativePath+"/"));
            if (used) {
                Node n=new Node("module:"+m.relativePath,m.artifactId,"module");
                n.measured=!config.selectedModulePaths.contains(m.relativePath);
                modules.put(m.relativePath,n);
            }
        }
        for (CoverageModuleResult m:coverage) {
            Node module=modules.get(m.modulePath); if (module==null) continue;
            module.measured="jacoco".equals(m.source);
            Map<String,Node> packages=new TreeMap<>();
            Set<String> seen=new HashSet<>();
            for (CoverageClassResult c:m.classes) {
                if (!seen.add(c.qualifiedName)) continue;
                Node pkg=packages.computeIfAbsent(c.packageName,p -> new Node(module.id+":"+p,p.isEmpty()?"(默认包)":p,"package"));
                Node leaf=new Node(module.id+":"+c.qualifiedName,c.className,"class");
                leaf.measured=module.measured; leaf.classCount=1;
                // Source fallback counters are estimates, not executable JaCoCo lines.
                if (leaf.measured) { leaf.coveredLines=c.coveredLines; leaf.totalLines=c.coveredLines+c.missedLines; }
                leaf.lineCoverage=leaf.measured&&leaf.totalLines>0?leaf.coveredLines*100.0/leaf.totalLines:null;
                pkg.children.add(leaf);
            }
            for (Node pkg:packages.values()) { pkg.aggregate(); module.children.add(pkg); }
        }
        for (var entry:modules.entrySet()) {
            String parent=modules.keySet().stream().filter(p -> !p.equals(entry.getKey())&&entry.getKey().startsWith(p+"/"))
                    .max(Comparator.comparingInt(String::length)).orElse(null);
            (parent==null?root:modules.get(parent)).children.add(entry.getValue());
        }
        aggregate(root);
        return root;
    }
    private static void aggregate(Node node) { for(Node child:node.children) if(!child.kind.equals("class")) aggregate(child); node.aggregate(); }
}
