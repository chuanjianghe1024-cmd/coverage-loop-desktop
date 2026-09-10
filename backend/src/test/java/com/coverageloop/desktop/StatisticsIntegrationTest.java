package com.coverageloop.desktop;

import com.coverageloop.model.*;
import com.coverageloop.service.*;
import com.coverageloop.util.Fs;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class StatisticsIntegrationTest {
    @TempDir Path temp;
    private ProjectConfig fixture() throws Exception {
        Path source=Path.of("src/test/resources/sample-project");
        try(var paths=Files.walk(source)) {
            for(Path path:paths.toList()) {
                Path to=temp.resolve(source.relativize(path).toString());
                if(Files.isDirectory(path)) Files.createDirectories(to); else Files.copy(path,to);
            }
        }
        ProjectConfig c=ConfigFactory.createDefaultConfig(temp.resolve("pom.xml").toString());
        c.maven.executable=System.getProperty("coverage.test.maven","");
        c.maven.settingsPath=System.getProperty("coverage.test.settings","");
        c.maven.localRepository="";
        c.selectedModulePaths=List.of("module-a");
        return c;
    }
    private static CoverageScope include(String module,String name) {
        CoverageScope s=new CoverageScope();s.modulePath=module;s.kind=ScopeKind.class_;s.mode=ScopeMode.include;s.pattern=name;return s;
    }
    @Test void selectedPackageTestsRunWithoutUnselectedModulesOrBroadPomIncludes() throws Exception {
        ProjectConfig c=fixture(); c.scopes=List.of(include("module-a","com.sample.a.Greeter"));
        c.maven.versionNumber="1.0.0";c.maven.forceUpdate=true;
        Path chosenTest=temp.resolve("module-a/src/test/java/com/sample/a/GreeterTest.java");
        Files.writeString(chosenTest,Files.readString(chosenTest).replace("class GreeterTest {", "class GreeterTest { @org.junit.jupiter.api.Test void receivesVersionParameter(){org.junit.jupiter.api.Assertions.assertEquals(\"1.0.0\",System.getProperty(\"version_number\"));}"));
        Fs.writeString(temp.resolve("module-a/src/main/java/com/other/Other.java").toString(),"package com.other; public class Other { public int value(){return 1;} }");
        Fs.writeString(temp.resolve("module-a/src/test/java/com/other/MustNotRunTest.java").toString(),"package com.other; class MustNotRunTest { @org.junit.jupiter.api.Test void fail(){throw new AssertionError(\"Unselected test ran\");} }");
        Path pom=temp.resolve("pom.xml");
        Files.writeString(pom,Files.readString(pom).replace("<version>3.2.5</version>","<version>3.2.5</version><configuration><includes><include>**/*.java</include></includes></configuration>"));
        var plan=ScopedTests.plan(c);
        assertEquals(1,plan.size());assertEquals(List.of("com.sample.a.GreeterTest"),plan.get(0).tests());
        Fs.writeString(temp.resolve("module-a/target/surefire-reports/TEST-com.other.MustNotRunTest.xml").toString(),"<testsuite tests=\"999\" failures=\"0\" errors=\"0\" skipped=\"0\"/>");
        MavenRunner.RunnerPaths paths=new MavenRunner.RunnerPaths();paths.resourcesPath=temp.toString();paths.developmentRoot=temp.toString();
        MavenRunner runner=new MavenRunner(paths,new MavenRunner.OutputSink(){public void output(MavenOutputEvent e){} public void progress(MavenProgressEvent e){}});
        MavenRunner.RunOptions options=new MavenRunner.RunOptions();options.scopeTests=true;
        MavenRunResult result=runner.run(c,null,options);
        assertEquals(0,result.exitCode,()->Fs.readStringQuiet(result.logPath));
        assertEquals(TestExecutionStatus.passed,result.tests.status,()->Fs.readStringQuiet(result.logPath));
        assertTrue(result.command.args.contains("-am"));assertTrue(result.command.preInstallArgs.contains("-am"));
        assertFalse(Files.exists(temp.resolve("module-b/target/surefire-reports")));
        assertFalse(Files.exists(temp.resolve("module-a/target/surefire-reports/TEST-com.other.MustNotRunTest.xml")));
        assertEquals(List.of("com.sample.a.Greeter"),result.coverage.get(0).classes.stream().map(x->x.qualifiedName).toList());
        assertTrue(Files.exists(Path.of(result.runDirectory,"round-001-scope/module-1-tests.include")));
        var tree=StatisticsTree.build(c,result.coverage);assertTrue(tree.measured);assertEquals(1,tree.classCount);assertNotNull(tree.lineCoverage);
    }
    @Test void multipleConfigurationsPersistAsSeparateFilesAndNeverReplaceLoopConfig() throws Exception {
        ProjectConfig c=fixture();
        try(WorkspaceStore store=new WorkspaceStore(temp.resolve("workspace.db"))) {
            store.saveConfig(c);c.id="first";c.name="订单";String first=store.saveStatisticsConfig(c);
            c=c.copy();c.id="second";c.name="库存";c.selectedModulePaths=List.of("module-b");store.saveStatisticsConfig(c);
            assertTrue(Files.exists(Path.of(first)));assertEquals(2,store.statisticsConfigs(c.rootPomPath).size());
        }
        try(WorkspaceStore store=new WorkspaceStore(temp.resolve("workspace.db"))) {
            assertEquals(2,store.statisticsConfigs(c.rootPomPath).size());
            assertEquals(List.of("module-a"),store.config(c.rootPomPath,"default").selectedModulePaths);
            store.deleteStatisticsConfig(c.rootPomPath,"first");assertEquals(1,store.statisticsConfigs(c.rootPomPath).size());
            assertTrue(Files.exists(WorkspaceStore.statisticsConfigPath(c)));
        }
    }
    @Test void reactorResolvesPlaceholderParentsAndSkipsDependencyTestsEvenWithIdenticalClassNames() throws Exception {
        temp=Files.createDirectories(temp.resolve("工程 空格"));
        ProjectConfig c=fixture();c.selectedModulePaths=List.of("module-b");c.maven.versionNumber="1.0.0";c.maven.parallelThreads=2;
        String group="com.coverageloop.reactor.g"+UUID.randomUUID().toString().replace("-","");
        for(String file:List.of("pom.xml","module-a/pom.xml","module-b/pom.xml")){
            Path pom=temp.resolve(file);String xml=Files.readString(pom).replace("<groupId>com.sample</groupId>","<groupId>"+group+"</groupId>").replace("<version>1.0.0</version>","<version>${version_number}</version>");
            if(file.equals("pom.xml"))xml=xml.replace("<properties>","<properties><version_number>1.0.0</version_number>").replace("<version>3.2.5</version>","<version>3.2.5</version><configuration><skipTests>false</skipTests><includes><include>**/*.java</include></includes></configuration><executions><execution><id>default-test</id><configuration><skipTests>false</skipTests><includes><include>**/*.java</include></includes></configuration></execution></executions>");
            if(file.startsWith("module-b"))xml=xml.replace("</project>","<dependencies><dependency><groupId>"+group+"</groupId><artifactId>module-a</artifactId><version>${version_number}</version></dependency></dependencies></project>");
            Files.writeString(pom,xml);
        }
        Fs.writeString(temp.resolve("module-a/src/test/java/com/sample/b/CalculatorTest.java").toString(),"package com.sample.b; class CalculatorTest { @org.junit.jupiter.api.Test void mustNotRun(){throw new AssertionError(\"dependency test with same name ran\");} }");
        Path chosen=temp.resolve("module-b/src/main/java/com/sample/b/Calculator.java");
        Files.writeString(chosen,Files.readString(chosen).replace("class Calculator {","class Calculator { public Class<?> dependencyType(){return com.sample.a.Greeter.class;}"));
        Map<Path,String> poms=new HashMap<>();for(String file:List.of("pom.xml","module-a/pom.xml","module-b/pom.xml"))poms.put(temp.resolve(file),Files.readString(temp.resolve(file)));
        var paths=new MavenRunner.RunnerPaths();paths.resourcesPath=temp.toString();paths.developmentRoot=temp.toString();
        var runner=new MavenRunner(paths,new MavenRunner.OutputSink(){public void output(MavenOutputEvent e){}public void progress(MavenProgressEvent e){}});
        var options=new MavenRunner.RunOptions();options.scopeTests=true;options.continueOnTestFailure=true;
        MavenRunResult first=runner.run(c,null,options);
        assertEquals(0,first.exitCode,()->Fs.readStringQuiet(first.logPath));assertEquals(TestExecutionStatus.passed,first.tests.status);
        assertTrue(first.preInstall.attempted);assertTrue(first.tests.tests>0);
        assertFalse(Files.exists(temp.resolve("module-a/target/surefire-reports")));
        assertTrue(Files.exists(temp.resolve("module-b/target/surefire-reports/TEST-com.sample.b.CalculatorTest.xml")));
        assertTrue(first.command.args.contains("-am"));assertEquals(temp.toRealPath().resolve("pom.xml"),Path.of(first.command.workingDirectory).resolve(first.command.args.get(first.command.args.indexOf("-f")+1)).toRealPath());
        String log=Files.readString(Path.of(first.logPath));assertEquals(1,log.split("\\[coverage\\] cwd=",-1).length-1);assertTrue(log.contains("coverage.loop.scope="));assertTrue(log.contains("JaCoCo output: target/jacoco.exec"));
        MavenRunResult next=runner.run(c,first.runId,options);assertEquals(0,next.exitCode,()->Fs.readStringQuiet(next.logPath));assertFalse(next.preInstall.attempted);assertEquals(first.tests.tests,next.tests.tests);
        assertTrue(Files.exists(Path.of(next.runDirectory,"round-002-scope/test-scope.properties.ready")));
        for(var entry:poms.entrySet())assertEquals(entry.getValue(),Files.readString(entry.getKey()));
        try(var jar=new java.util.jar.JarFile(Path.of(first.runDirectory,"round-001-scope/reactor-scope.jar").toFile());var input=new java.io.DataInputStream(jar.getInputStream(jar.getJarEntry("com/coverageloop/maven/ScopeParticipant.class")))){
            input.readInt();input.readUnsignedShort();assertEquals(52,input.readUnsignedShort(),"Target Maven extension must support Java 8");
        }
    }
    @Test void treeUsesLineWeightsAndDoesNotPresentSourceEstimatesAsMeasuredLines() throws Exception {
        ProjectConfig c=fixture();c.selectedModulePaths=List.of("module-a","module-b");
        CoverageModuleResult a=module("module-a","jacoco",10,0),b=module("module-b","jacoco",0,90);
        var tree=StatisticsTree.build(c,List.of(a,b));assertEquals(10,tree.coveredLines);assertEquals(100,tree.totalLines);assertEquals(10.0,tree.lineCoverage);
        b.source="source-fallback";tree=StatisticsTree.build(c,List.of(a,b));assertFalse(tree.measured);assertNull(tree.lineCoverage);
        var missing=tree.children.get(1);assertEquals(0,missing.totalLines);assertNull(missing.children.get(0).children.get(0).lineCoverage);
    }
    @Test void dependencyFailureIsNotAnUncoveredBaselineAndRecoveryCanEstablishOne() throws Exception {
        ProjectConfig c=fixture();c.maven.preInstall=false;c.maven.extraArgs=List.of("-o");
        Path pom=temp.resolve("module-a/pom.xml");String original=Files.readString(pom);
        Files.writeString(pom,original.replace("</project>","<dependencies><dependency><groupId>com.coverageloop.missing</groupId><artifactId>missing-"+UUID.randomUUID()+"</artifactId><version>1.0.0</version></dependency></dependencies></project>"));
        var paths=new MavenRunner.RunnerPaths();paths.resourcesPath=temp.toString();paths.developmentRoot=temp.toString();
        var runner=new MavenRunner(paths,new MavenRunner.OutputSink(){public void output(MavenOutputEvent e){}public void progress(MavenProgressEvent e){}});
        var options=new MavenRunner.RunOptions();options.scopeTests=true;options.continueOnTestFailure=true;
        MavenRunResult failed=runner.run(c,null,options);
        assertNotEquals(0,failed.exitCode);assertEquals(TestExecutionStatus.build_failed,failed.tests.status);assertEquals("dependency-resolution",failed.tests.failureKind);assertEquals(0,failed.tests.tests);
        assertTrue(failed.tests.message.contains("覆盖率无效"));assertFalse(Files.exists(Path.of(failed.runDirectory,"baseline-coverage.json")));
        String gate=Files.readString(Path.of(failed.runDirectory,"round-001-coverage-gate.txt"));assertTrue(gate.contains("COVERAGE_NOT_EVALUATED"));assertFalse(gate.contains("COVERAGE_GATE_FAILED"));
        Files.writeString(pom,original);MavenRunResult recovered=runner.run(c,failed.runId,options);
        assertEquals(0,recovered.exitCode,()->Fs.readStringQuiet(recovered.logPath));assertEquals(TestExecutionStatus.passed,recovered.tests.status);
        assertTrue(Files.exists(Path.of(recovered.runDirectory,"baseline-coverage.json")));
    }
    @Test void defaultPackageSelectionIncludesOnlyDefaultPackage() {
        CoverageScope scope=new CoverageScope();scope.kind=ScopeKind.package_;scope.pattern="";scope.mode=ScopeMode.include;
        assertTrue(ScopeSelection.isSelectedClass(List.of(scope),"Example"));assertFalse(ScopeSelection.isSelectedClass(List.of(scope),"pkg.Example"));
    }
    @Test void exclusionFileMatchesOnlyClassesOutsideSelectionIncludingUnknownGeneratedTests() {
        var selection=new ScopedTests.Selection("module-a",2,List.of("demo"),List.of("demo.AlphaTest","demo.AlphabetTest","RootTest"));
        ScopedTests.writeFilters(selection,temp,1);
        String line=Fs.readStringQuiet(temp.resolve("module-1-tests.exclude").toString()).trim();
        assertFalse(line.contains("!"));
        var regex=java.util.regex.Pattern.compile(line.substring(7,line.length()-1));
        for(String allowed:List.of("demo/AlphaTest.class","demo/AlphabetTest.class","demo/AlphaTest$Nested.class","RootTest.class","demo\\AlphaTest.class","demo\\AlphaTest$Nested.class"))
            assertFalse(regex.matcher(allowed).matches(),allowed);
        for(String excluded:List.of("demo/AlphaTest2.class","demo/Alph.class","demo/GeneratedTest.class","elsewhere/AlphaTest.class","RootTests.class","Root.class","demo\\AlphaTest2.class","demo\\GeneratedTest.class","elsewhere\\AlphaTest.class"))
            assertTrue(regex.matcher(excluded).matches(),excluded);
    }
    @Test void failedSelectedTestsDoNotPreventOtherModulesAndNoTestsStayUnmeasured() throws Exception {
        ProjectConfig c=fixture();c.selectedModulePaths=List.of("module-a","module-b","module-c");
        Path root=temp.resolve("pom.xml");Files.writeString(root,Files.readString(root).replace("</modules>","<module>module-c</module></modules>"));
        Fs.writeString(temp.resolve("module-c/pom.xml").toString(),Files.readString(temp.resolve("module-b/pom.xml")).replace("module-b","module-c"));
        Fs.writeString(temp.resolve("module-c/src/main/java/demo/Empty.java").toString(),"package demo; public class Empty { public int value(){return 42;} }");
        Fs.writeString(temp.resolve("module-a/src/test/java/com/sample/a/SelectedFailureTest.java").toString(),"package com.sample.a; class SelectedFailureTest { @org.junit.jupiter.api.Test void fails(){throw new AssertionError(\"selected test failed\");} }");
        MavenRunner.RunnerPaths paths=new MavenRunner.RunnerPaths();paths.resourcesPath=temp.toString();paths.developmentRoot=temp.toString();
        List<MavenProgressEvent> progress=new ArrayList<>();
        MavenRunner runner=new MavenRunner(paths,new MavenRunner.OutputSink(){public void output(MavenOutputEvent e){}public synchronized void progress(MavenProgressEvent e){progress.add(e);}});
        MavenRunner.RunOptions options=new MavenRunner.RunOptions();options.scopeTests=true;options.continueOnTestFailure=true;
        MavenRunResult result=runner.run(c,null,options);
        assertEquals(0,result.exitCode,()->Fs.readStringQuiet(result.logPath));assertEquals(TestExecutionStatus.test_failed,result.tests.status);
        assertEquals(1,result.tests.failures);assertTrue(Files.exists(temp.resolve("module-b/target/surefire-reports/TEST-com.sample.b.CalculatorTest.xml")));
        assertEquals(List.of("module-c"),result.noTestModules);assertTrue(CoverageLoopRunner.canCollectCoverage(result));assertFalse(CoverageLoopRunner.hasVerifiedCoverage(result));
        var states=result.moduleProgress.stream().filter(m->m.phase.equals("coverage")&&!m.dependency).toList();assertEquals(List.of("failed","success","skipped"),states.stream().map(m->m.status).toList());
        var tree=StatisticsTree.build(c,result.coverage);assertNull(tree.lineCoverage);assertNotNull(tree.children.get(1).lineCoverage);assertNull(tree.children.get(2).lineCoverage);
        assertTrue(progress.stream().anyMatch(e->e.modules.stream().anyMatch(m->m.modulePath.equals("module-a")&&m.status.equals("running"))));
    }
    @Test void packageWithOwnClassesContainsItsSubpackagesAndSumsEachLineOnce() throws Exception {
        ProjectConfig c=fixture();CoverageModuleResult m=module("module-a","jacoco",2,3);m.classes=new ArrayList<>(m.classes);
        CoverageClassResult a=m.classes.get(0);a.packageName="api";a.qualifiedName="api.A";a.className="A";
        for(String pkg:List.of("api.conf","api.dep")){CoverageClassResult leaf=new CoverageClassResult();leaf.packageName=pkg;leaf.qualifiedName=pkg+".B";leaf.className="B";leaf.coveredLines=1;leaf.missedLines=4;m.classes.add(leaf);}
        var root=StatisticsTree.build(c,List.of(m));var api=root.children.get(0).children.get(0);
        assertEquals("api",api.name);assertEquals(3,api.children.size());assertEquals(4,api.coveredLines);assertEquals(15,api.totalLines);assertEquals(3,root.classCount);
        assertEquals(List.of("conf","dep"),api.children.stream().filter(n->n.kind.equals("package")).map(n->n.name).toList());
    }
    private static CoverageModuleResult module(String name,String source,int covered,int missed) {
        CoverageModuleResult m=new CoverageModuleResult();m.modulePath=name;m.source=source;
        CoverageClassResult c=new CoverageClassResult();c.modulePath=name;c.packageName="demo";c.className="Example";c.qualifiedName="demo.Example";c.coveredLines=covered;c.missedLines=missed;
        m.classes=List.of(c);m.classCount=1;return m;
    }
}
