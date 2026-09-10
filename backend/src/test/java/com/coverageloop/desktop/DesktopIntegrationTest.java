package com.coverageloop.desktop;

import com.coverageloop.model.*;
import com.coverageloop.service.CoverageLoopRunner;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class DesktopIntegrationTest {
    @TempDir Path temp;
    @Test void sqlitePersistsConfigurationRoundsAndRecoversInterruptedJobs() throws Exception {
        Path file=temp.resolve("workspace.db");
        ProjectConfig c=ConfigFactory.createDefaultConfig(temp.resolve("pom.xml").toString());
        c.agent.coveragePromptTemplate="保存自定义提示词：{{targetClasses}}";
        Map<String,Object> state=new LinkedHashMap<>();
        state.put("id","job-1");state.put("status","running");state.put("startedAt","2026-09-10T00:00:00Z");
        try(WorkspaceStore store=new WorkspaceStore(file)) {
            store.touchProject(c.rootPomPath,"sample");store.saveConfig(c);store.saveJob(state,c);
            MavenRunResult round=new MavenRunResult();round.round=1;round.tests.tests=12;
            store.saveRound("job-1",round);
            assertEquals(1,store.projects().size());
        }
        try(WorkspaceStore store=new WorkspaceStore(file)) {
            assertEquals(c.agent.coveragePromptTemplate,store.config(c.rootPomPath,"default").agent.coveragePromptTemplate);
            JsonObject job=store.detail(c.rootPomPath,"job-1");
            assertEquals("interrupted",job.get("status").getAsString());
            assertEquals(12,job.getAsJsonArray("rounds").get(0).getAsJsonObject().getAsJsonObject("tests").get("tests").getAsInt());
            assertThrows(IllegalArgumentException.class,()->store.detail("other-project","job-1"));
        }
        assertEquals("SQLite format 3",new String(Files.readAllBytes(file),0,15));
    }
    @Test void invalidOrEmptyCoverageNeverSatisfiesTheGate() {
        MavenRunResult r=new MavenRunResult();assertFalse(CoverageLoopRunner.hasVerifiedCoverage(r));
        CoverageModuleResult module=new CoverageModuleResult();module.source="source-fallback";module.classCount=2;r.coverage.add(module);
        assertFalse(CoverageLoopRunner.hasVerifiedCoverage(r));
        module.source="jacoco";assertTrue(CoverageLoopRunner.hasVerifiedCoverage(r));
        module.classCount=0;assertFalse(CoverageLoopRunner.hasVerifiedCoverage(r));
    }
    @Test void serverRejectsBrowserOriginsAndMissingAuthentication() throws Exception {
        String token="integration-test-token-32-characters";
        try(DesktopServer server=new DesktopServer(token,new DesktopEngine(temp.resolve("api.db")))) {
            server.start();HttpClient client=HttpClient.newHttpClient();URI health=URI.create("http://127.0.0.1:"+server.port()+"/health");
            assertEquals(401,client.send(HttpRequest.newBuilder(health).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(401,client.send(HttpRequest.newBuilder(health).header("Authorization","Bearer "+token).header("Origin","https://untrusted.test").GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(200,client.send(HttpRequest.newBuilder(health).header("Authorization","Bearer "+token).GET().build(),HttpResponse.BodyHandlers.ofString()).statusCode());
            URI open=URI.create("http://127.0.0.1:"+server.port()+"/project/open");
            Files.writeString(temp.resolve("pom.xml"),"<project><modelVersion>4.0.0</modelVersion><groupId>x</groupId><artifactId>single</artifactId><version>1</version></project>");
            String body="{\"rootPomPath\":"+com.coverageloop.util.Json.toCompactJson(temp.toString())+"}";
            var response=client.send(HttpRequest.newBuilder(open).header("Authorization","Bearer "+token).POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,response.statusCode(),response.body());assertTrue(response.body().contains("single"));
        }
    }
}
