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
        assertFalse(result.command.args.contains("-am"));assertTrue(result.command.preInstallArgs.contains("-am"));
        assertFalse(Files.exists(temp.resolve("module-b/target/surefire-reports")));
        assertFalse(Files.exists(temp.resolve("module-a/target/surefire-reports/TEST-com.other.MustNotRunTest.xml")));
        assertEquals(List.of("com.sample.a.Greeter"),result.coverage.get(0).classes.stream().map(x->x.qualifiedName).toList());
        assertTrue(Files.exists(Path.of(result.runDirectory,"module-1-tests.include")));
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
    @Test void treeUsesLineWeightsAndDoesNotPresentSourceEstimatesAsMeasuredLines() throws Exception {
        ProjectConfig c=fixture();c.selectedModulePaths=List.of("module-a","module-b");
        CoverageModuleResult a=module("module-a","jacoco",10,0),b=module("module-b","jacoco",0,90);
        var tree=StatisticsTree.build(c,List.of(a,b));assertEquals(10,tree.coveredLines);assertEquals(100,tree.totalLines);assertEquals(10.0,tree.lineCoverage);
        b.source="source-fallback";tree=StatisticsTree.build(c,List.of(a,b));assertFalse(tree.measured);assertNull(tree.lineCoverage);
        var missing=tree.children.get(1);assertEquals(0,missing.totalLines);assertNull(missing.children.get(0).children.get(0).lineCoverage);
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
    private static CoverageModuleResult module(String name,String source,int covered,int missed) {
        CoverageModuleResult m=new CoverageModuleResult();m.modulePath=name;m.source=source;
        CoverageClassResult c=new CoverageClassResult();c.modulePath=name;c.packageName="demo";c.className="Example";c.qualifiedName="demo.Example";c.coveredLines=covered;c.missedLines=missed;
        m.classes=List.of(c);m.classCount=1;return m;
    }
}
