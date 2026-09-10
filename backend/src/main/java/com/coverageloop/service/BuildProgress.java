package com.coverageloop.service;

import com.coverageloop.model.*;
import java.time.Instant;
import java.util.*;
import java.util.regex.*;

/** Incremental line parser: retain module state across arbitrary stdout chunks. */
public final class BuildProgress {
    public static final class ModuleState {
        public String key,modulePath,name,phase,stage="waiting",status="waiting",startedAt,finishedAt;
        public boolean dependency;
    }
    private final List<MavenModule> modules;
    private final Set<String> selected;
    private final Map<String,ModuleState> states=new LinkedHashMap<>();
    private final Map<String,StringBuilder> pending=new HashMap<>();
    private final boolean parallel;
    private String phase="pre-install",active;
    private boolean reactorListing;
    private static final Pattern HEADER=Pattern.compile("<\\s*[^:<> ]+:([^<> ]+)\\s*>");
    private static final Pattern GOAL=Pattern.compile("---\\s+([^ ]+):([^ :]+)\\s+.*?@\\s+([^ ]+)\\s+---");
    private static final Pattern FAILURE=Pattern.compile("on project ([^: ]+)");
    private static final Pattern SUMMARY=Pattern.compile("\\[INFO]\\s+(.+?)\\s+\\.{2,}\\s+(SUCCESS|FAILURE|SKIPPED)");
    public BuildProgress(ProjectConfig config){modules=ProjectScanner.scanMavenProject(config.rootPomPath).modules;selected=new HashSet<>(config.selectedModulePaths);parallel=config.maven.parallelThreads>1||config.maven.extraArgs.stream().anyMatch(a->a.startsWith("-T")||a.startsWith("--threads"));}
    public synchronized void begin(String phase){flush();this.phase=phase;active=null;reactorListing=false;}
    public synchronized void queueTests(){for(MavenModule m:modules)if(selected.contains(m.relativePath))state(m,"coverage");}
    private ModuleState state(MavenModule m,String phase){return states.computeIfAbsent(phase+":"+m.relativePath,k->{ModuleState s=new ModuleState();s.key=k;s.modulePath=m.relativePath;s.name=m.artifactId;s.phase=phase;s.dependency=!selected.contains(m.relativePath);return s;});}
    private MavenModule find(String name){return modules.stream().filter(m->name.equals(m.artifactId)||name.equals(m.name)||name.equals(m.relativePath)).findFirst().orElse(null);}
    private ModuleState activate(String name){MavenModule m=find(name);if(m==null)return null;ModuleState s=state(m,phase);if(!parallel&&active!=null&&!active.equals(s.key))finish(states.get(active),"success");active=s.key;if(s.startedAt==null)s.startedAt=Instant.now().toString();if(s.status.equals("waiting")){s.status="running";s.stage="scanning";}return s;}
    private void finish(ModuleState s,String status){if(s==null||s.status.equals("failed")||s.status.equals("skipped"))return;s.status=status;s.finishedAt=Instant.now().toString();}
    public synchronized void module(String path,String stage,String status){MavenModule m=find(path);if(m==null)return;ModuleState s=state(m,phase);active=s.key;s.stage=stage;s.status=status;if(s.startedAt==null)s.startedAt=Instant.now().toString();if(!status.equals("running")&&!status.equals("waiting"))s.finishedAt=Instant.now().toString();}
    public synchronized void feed(String stream,String chunk){StringBuilder b=pending.computeIfAbsent(stream,k->new StringBuilder());b.append(chunk);int end;while((end=b.indexOf("\n"))>=0){line(b.substring(0,end));b.delete(0,end+1);}if(b.length()>32768)b.delete(0,b.length()-32768);}
    public synchronized void flush(){for(StringBuilder b:pending.values()){if(!b.isEmpty())line(b.toString());b.setLength(0);}}
    private void line(String input){String text=com.coverageloop.util.Text.stripAnsi(input).trim();
        if(text.contains("Reactor Build Order:"))reactorListing=true;
        if(reactorListing){Matcher order=Pattern.compile("\\[INFO]\\s+(.+?)\\s+\\[[A-Za-z]+]\\s*$").matcher(text);if(order.find()){MavenModule m=find(order.group(1).trim());if(m!=null)state(m,phase);}}
Matcher h=HEADER.matcher(text);if(h.find()){reactorListing=false;activate(h.group(1));}Matcher g=GOAL.matcher(text);if(g.find()){reactorListing=false;ModuleState s=activate(g.group(3));if(s!=null)s.stage=stage(g.group(1)+":"+g.group(2));}Matcher f=FAILURE.matcher(text);if(text.contains("[ERROR]")&&f.find()){ModuleState s=activate(f.group(1));finish(s,"failed");}Matcher summary=SUMMARY.matcher(text);if(summary.find()){MavenModule m=find(summary.group(1).trim());if(m!=null){ModuleState s=state(m,phase);finish(s,switch(summary.group(2)){case "SUCCESS"->"success";case "SKIPPED"->"skipped";default->"failed";});}}
    }
    private static String stage(String goal){String v=goal.toLowerCase();if(v.contains("proguard"))return "proguard";if(v.contains("jacoco")&&v.contains("report"))return "report";if(v.contains("surefire")||v.contains("failsafe"))return "test";if(v.contains("testcompile"))return "test-compile";if(v.contains("compiler"))return "compile";if(v.contains("install"))return "install";if(v.contains("jar")||v.contains("shade")||v.contains("assembly"))return "package";if(v.contains("resources"))return "resources";return goal;}
    public synchronized void end(int exit){flush();for(ModuleState s:states.values())if(s.phase.equals(phase)&&s.status.equals("running"))finish(s,exit==0?"success":"failed");if(exit!=0)for(ModuleState s:states.values())if(s.phase.equals(phase)&&s.status.equals("waiting"))finish(s,"skipped");active=null;}
    public synchronized void finishTask(boolean failed){flush();for(ModuleState s:states.values()){if(s.status.equals("running"))finish(s,failed?"failed":"success");else if(s.status.equals("waiting"))finish(s,"skipped");}}
    public synchronized List<ModuleState> snapshot(){List<ModuleState> result=new ArrayList<>();for(ModuleState s:states.values()){ModuleState c=new ModuleState();c.key=s.key;c.modulePath=s.modulePath;c.name=s.name;c.phase=s.phase;c.stage=s.stage;c.status=s.status;c.startedAt=s.startedAt;c.finishedAt=s.finishedAt;c.dependency=s.dependency;result.add(c);}return result;}
}
