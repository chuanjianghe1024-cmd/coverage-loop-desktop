package com.coverageloop.desktop;

import com.coverageloop.model.*;
import com.coverageloop.service.*;
import com.coverageloop.util.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class VersionAndHistoryTest {
    @TempDir Path temp;
    private ProjectConfig config() throws Exception {
        Path pom=temp.resolve("pom.xml");Files.writeString(pom,"<project><modelVersion>4.0.0</modelVersion><groupId>demo</groupId><artifactId>sample</artifactId><version>1.0.0</version></project>");
        return ConfigFactory.createDefaultConfig(pom.toString());
    }
    @Test void oldExtraArgumentsMigrateAndExplicitVersionWinsWithoutDuplicateFlags() throws Exception {
        ProjectConfig c=config();c.maven.extraArgs=List.of("-Dversion_number=0.9.0","-Dversion_number=1.0.0","--update-snapshots","-X");
        c=ConfigStore.parseConfig(Json.toJson(c),c.rootPomPath);
        assertEquals("1.0.0",c.maven.versionNumber);assertTrue(c.maven.forceUpdate);assertEquals(List.of("-X"),c.maven.extraArgs);
        c.maven.versionNumber="2.0.0";c.maven.extraArgs=List.of("-Dversion_number=old","-U");
        c=ConfigStore.parseConfig(Json.toJson(c),c.rootPomPath);assertEquals("2.0.0",c.maven.versionNumber);
        var paths=new MavenRunner.RunnerPaths();paths.resourcesPath=temp.toString();paths.developmentRoot=temp.toString();
        var runner=new MavenRunner(paths,new MavenRunner.OutputSink(){public void output(MavenOutputEvent e){}public void progress(MavenProgressEvent e){}});
        var options=new MavenRunner.RunOptions();options.scopeTests=true;
        var command=runner.resolveMavenCommand(c.copy(),options);
        for(var args:List.of(command.args,command.preInstallArgs)){
            assertEquals(1,args.stream().filter(a->a.equals("-Dversion_number=2.0.0")).count());assertEquals(1,Collections.frequency(args,"-U"));assertFalse(args.contains("-Dversion_number=old"));
        }
        c.maven.versionNumber="";c.maven.forceUpdate=false;
        c=ConfigStore.parseConfig(Json.toJson(c),c.rootPomPath);
        command=runner.resolveMavenCommand(c,options);assertFalse(command.args.contains("-U"));assertFalse(command.args.stream().anyMatch(a->a.startsWith("-Dversion_number=")));
    }
    @Test void versionFieldsSurviveBothConfigurationStores() throws Exception {
        ProjectConfig c=config();c.maven.versionNumber="1.0.0-SNAPSHOT";c.maven.forceUpdate=true;
        try(var store=new WorkspaceStore(temp.resolve("db"))){store.saveConfig(c);store.saveStatisticsConfig(c);}
        try(var store=new WorkspaceStore(temp.resolve("db"))){
            assertEquals(c.maven.versionNumber,store.config(c.rootPomPath,c.id).maven.versionNumber);assertTrue(store.statisticsConfigs(c.rootPomPath).get(0).maven.forceUpdate);
        }
    }
    private Path job(WorkspaceStore store,ProjectConfig c,String id,String status) throws Exception {
        Path directory=Files.createDirectories(temp.resolve(".coverage-loop/runs/default/"+id));Files.writeString(directory.resolve("round-001-maven.log"),id);
        var state=new LinkedHashMap<String,Object>();state.put("id",id);state.put("status",status);state.put("startedAt","2026-09-10T00:00:00Z");store.saveJob(state,c);
        for(int n=1;n<=2;n++){MavenRunResult r=new MavenRunResult();r.round=n;r.runId=id;r.runDirectory=directory.toString();store.saveRound(id,r);}
        return directory;
    }
    @Test void deletingARecordRemovesAllRoundsButKeepsItsConfigAndFilesByDefault() throws Exception {
        ProjectConfig c=config();Path directory;
        try(var store=new WorkspaceStore(temp.resolve("db"))){store.saveConfig(c);directory=job(store,c,"first","completed");job(store,c,"second","completed");store.deleteJob(c.rootPomPath,"first",false);
            assertEquals(1,store.history(c.rootPomPath).size());assertThrows(IllegalArgumentException.class,()->store.detail(c.rootPomPath,"first"));assertEquals(2,store.detail(c.rootPomPath,"second").getAsJsonArray("rounds").size());
        }
        try(var store=new WorkspaceStore(temp.resolve("db"))){assertEquals(1,store.history(c.rootPomPath).size());assertNotNull(store.config(c.rootPomPath,c.id));}
        assertTrue(Files.exists(directory.resolve("round-001-maven.log")));assertTrue(Files.exists(Path.of(c.rootPomPath)));
    }
    @Test void optionalFileCleanupAffectsOnlyTheSelectedRun() throws Exception {
        ProjectConfig c=config();
        try(var store=new WorkspaceStore(temp.resolve("db"))){Path selected=job(store,c,"first","completed"),other=job(store,c,"second","completed");
            assertEquals("",store.deleteJob(c.rootPomPath,"first",true));assertFalse(Files.exists(selected));assertTrue(Files.exists(other.resolve("round-001-maven.log")));assertTrue(Files.exists(Path.of(c.rootPomPath)));
        }
    }
    @Test void activeWrongProjectAndOutOfBoundsRecordsCannotBeRemoved() throws Exception {
        ProjectConfig c=config();
        try(var store=new WorkspaceStore(temp.resolve("db"))){
            Path active=job(store,c,"active","running");assertThrows(IllegalStateException.class,()->store.deleteJob(c.rootPomPath,"active",true));assertTrue(Files.exists(active));
            job(store,c,"finished","completed");assertThrows(IllegalArgumentException.class,()->store.deleteJob("other-project","finished",false));
            MavenRunResult wrong=new MavenRunResult();wrong.round=3;wrong.runDirectory=temp.toString();store.saveRound("finished",wrong);
            assertThrows(java.io.IOException.class,()->store.deleteJob(c.rootPomPath,"finished",true));assertNotNull(store.detail(c.rootPomPath,"finished"));assertTrue(Files.exists(Path.of(c.rootPomPath)));
            store.deleteJob(c.rootPomPath,"finished",false);assertEquals("running",store.detail(c.rootPomPath,"active").get("status").getAsString());
        }
    }
    @Test void sharedRunDirectoriesAreRetainedAndFailedStagingCanBeRestored() throws Exception {
        ProjectConfig c=config();
        try(var store=new WorkspaceStore(temp.resolve("db"))){
            Path first=job(store,c,"first","completed");job(store,c,"second","completed");MavenRunResult shared=new MavenRunResult();shared.round=3;shared.runDirectory=first.toString();store.saveRound("second",shared);
            assertThrows(java.io.IOException.class,()->store.deleteJob(c.rootPomPath,"first",true));assertTrue(Files.exists(first));assertNotNull(store.detail(c.rootPomPath,"first"));
            var staged=HistoryArtifacts.stage(c.rootPomPath,store.detail(c.rootPomPath,"first"),Set.of());assertFalse(Files.exists(first));staged.restore();assertTrue(Files.exists(first.resolve("round-001-maven.log")));
        }
    }
}
