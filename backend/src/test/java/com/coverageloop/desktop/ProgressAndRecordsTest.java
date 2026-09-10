package com.coverageloop.desktop;

import com.coverageloop.model.*;
import com.coverageloop.service.*;
import com.coverageloop.util.*;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ProgressAndRecordsTest {
    @TempDir Path temp;
    ProjectConfig config(){return ConfigFactory.createDefaultConfig(Path.of("src/test/resources/sample-project/pom.xml").toAbsolutePath().toString());}
    @Test void reactorProgressHandlesSplitLinesAndKeepsFailuresWhenReactorContinues(){
        ProjectConfig c=config();c.selectedModulePaths=List.of("module-a","module-b");BuildProgress p=new BuildProgress(c);p.queueTests();p.begin("pre-install");
        p.feed("stdout","[INFO] Reactor Build Order:\n[INFO] module-a [jar]\n[INFO] module-b [jar]\n[INFO] --- compiler:3.13.0:com");
        assertEquals(4,p.snapshot().size());assertTrue(p.snapshot().stream().allMatch(m->m.status.equals("waiting")));
        p.feed("stdout","pile (default-compile) @ module-a ---\n");
        assertEquals("compile",p.snapshot().stream().filter(m->m.key.equals("pre-install:module-a")).findFirst().orElseThrow().stage);
        p.feed("stdout","[INFO] --- proguard:2.6.1:proguard (default) @ module-a ---\n[INFO] --- compiler:3.13.0:testCompile (default) @ module-b ---\n");
        assertEquals("success",p.snapshot().get(2).status);assertEquals("test-compile",p.snapshot().get(3).stage);
        p.feed("stderr","[ERROR] Failed to execute goal on project module-b: compile failed\n");p.end(1);p.finishTask(true);
        assertEquals("success",p.snapshot().get(2).status);assertEquals("failed",p.snapshot().get(3).status);assertEquals("skipped",p.snapshot().get(0).status);
    }
    @Test void parallelModulesStayRunningUntilTheirOwnCompletion(){
        ProjectConfig c=config();c.maven.parallelThreads=2;BuildProgress p=new BuildProgress(c);p.begin("pre-install");
        p.feed("stdout","[INFO] --- compiler:3.13.0:compile (default) @ module-a ---\n[INFO] --- compiler:3.13.0:compile (default) @ module-b ---\n");
        assertEquals(2,p.snapshot().stream().filter(m->m.status.equals("running")).count());
        p.feed("stdout","[INFO] module-a ........ SUCCESS [ 1s]\n[INFO] module-b ........ FAILURE [ 2s]\n");
        assertEquals(List.of("success","failed"),p.snapshot().stream().map(m->m.status).toList());
    }
    @Test void recoverOnlyExplicitNativeSessionsAndPreserveHermesProfile(){
        ProjectConfig c=config();c.agent.extraArgs=List.of("--profile","competition");
        assertEquals("20260910_120000_abc123",AgentSessions.sessionId("hermes","session_id: 20260910_120000_abc123\n"));
        assertNull(AgentSessions.sessionId("hermes","runId: 20260910_120000_abc123"));
        assertEquals(List.of("--profile","competition","chat","--resume","20260910_120000_abc123"),AgentSessions.recoveryArgs(c,"20260910_120000_abc123"));
        assertThrows(IllegalArgumentException.class,()->AgentSessions.recoveryArgs(c,"latest; echo bad"));
        assertEquals("ses_ABC123",AgentSessions.sessionId("opencode","{\"sessionID\":\"ses_ABC123\",\"part\":{\"text\":\"DONE\"}}"));
        assertEquals("DONE\n",AgentSessions.readable("{\"sessionID\":\"ses_ABC123\",\"part\":{\"text\":\"DONE\"}}"));
    }
    @Test void evidenceTabsUseRoundFilesAndRecoveryUsesPersistedConfiguration() throws Exception {
        Path pom=temp.resolve("pom.xml");Files.writeString(pom,"<project/>");ProjectConfig c=ConfigFactory.createDefaultConfig(pom.toString());
        Path directory=Files.createDirectories(temp.resolve(".coverage-loop/runs/default/run-one"));
        MavenRunResult first=new MavenRunResult();first.round=1;first.runId="run-one";first.runDirectory=directory.toString();first.finishedAt="2026-09-10T01:00:00Z";
        Files.writeString(directory.resolve("round-001-coverage.json"),"{\"round\":1}");Files.writeString(directory.resolve("round-001-session.json"),"{\"round\":1}");Files.writeString(directory.resolve("session.json"),"{\"round\":2}");
        AgentRoundResult agent=new AgentRoundResult();agent.round=1;agent.sessionId="20260910_120000_abc123";agent.finishedAt=first.finishedAt;
        try(WorkspaceStore store=new WorkspaceStore(temp.resolve("workspace.db"))){
            store.saveJob(Map.of("id","job","status","completed","startedAt",first.finishedAt,"agentRounds",List.of(agent)),c);store.saveRound("job",first);
            RoundRecords records=new RoundRecords(store);JsonObject input=new JsonObject();input.addProperty("rootPomPath",pom.toString());input.addProperty("id","job");input.addProperty("round",1);
            var list=JsonParser.parseString(Json.toJson(records.list(input))).getAsJsonObject();assertEquals(6,list.getAsJsonArray("files").size());
            input.addProperty("name","session.json");assertTrue(Json.toJson(records.read(input)).contains("\\\"round\\\":1"));assertFalse(Json.toJson(records.read(input)).contains("\\\"round\\\":2"));
            var recovery=JsonParser.parseString(Json.toJson(records.recovery(input))).getAsJsonObject();assertEquals("20260910_120000_abc123",recovery.get("sessionId").getAsString());
            assertEquals(List.of("chat","--resume","20260910_120000_abc123"),Json.fromJson(recovery.get("args").toString(),List.class));
            input.addProperty("name","../pom.xml");assertThrows(IllegalArgumentException.class,()->records.read(input));
            input.addProperty("name","maven.log");Files.writeString(directory.resolve("round-001-maven.log"),"x".repeat(600_000));assertTrue(Json.toJson(records.read(input)).contains("\"truncated\": true"));
            input.addProperty("round",2);assertThrows(IllegalArgumentException.class,()->records.list(input));
        }
    }
}
