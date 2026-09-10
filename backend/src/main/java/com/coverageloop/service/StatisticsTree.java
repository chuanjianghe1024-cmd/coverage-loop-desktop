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
                Node pkg=packageNode(module,packages,c.packageName);
                Node leaf=new Node(module.id+":"+c.qualifiedName,c.className,"class");
                leaf.measured=module.measured; leaf.classCount=1;
                // Source fallback counters are estimates, not executable JaCoCo lines.
                if (leaf.measured) { leaf.coveredLines=c.coveredLines; leaf.totalLines=c.coveredLines+c.missedLines; }
                leaf.lineCoverage=leaf.measured&&leaf.totalLines>0?leaf.coveredLines*100.0/leaf.totalLines:null;
                pkg.children.add(leaf);
            }
            module.children=new ArrayList<>(module.children.stream().map(StatisticsTree::compactPackage).toList());
        }
        for (var entry:modules.entrySet()) {
            String parent=modules.keySet().stream().filter(p -> !p.equals(entry.getKey())&&entry.getKey().startsWith(p+"/"))
                    .max(Comparator.comparingInt(String::length)).orElse(null);
            (parent==null?root:modules.get(parent)).children.add(entry.getValue());
        }
        aggregate(root);
        return root;
    }
    private static Node packageNode(Node module,Map<String,Node> packages,String path) {
        if(packages.containsKey(path)) return packages.get(path);
        int dot=path.lastIndexOf('.');
        Node parent=dot<0?module:packageNode(module,packages,path.substring(0,dot));
        Node node=new Node(module.id+":"+path,path.isEmpty()?"(默认包)":path.substring(dot+1),"package");
        packages.put(path,node);parent.children.add(node);return node;
    }
    private static Node compactPackage(Node node) {
        node.children=node.children.stream().map(c -> c.kind.equals("package")?compactPackage(c):c).toList();
        if(node.kind.equals("package")&&node.children.size()==1&&node.children.get(0).kind.equals("package")) {
            Node child=node.children.get(0);child.name=node.name+"."+child.name;return child;
        }
        return node;
    }
    private static void aggregate(Node node) { for(Node child:node.children) if(!child.kind.equals("class")) aggregate(child); node.aggregate(); }
}
