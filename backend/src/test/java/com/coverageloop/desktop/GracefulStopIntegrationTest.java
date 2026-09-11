package com.coverageloop.desktop;

import com.coverageloop.model.*;
import com.coverageloop.util.Json;
import com.coverageloop.util.Proc;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import java.util.Map;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(25)
class GracefulStopIntegrationTest {
    @TempDir Path temp;

    private ProjectConfig project() throws Exception {
        Files.writeString(temp.resolve("pom.xml"),"<project><modelVersion>4.0.0</modelVersion><groupId>x</groupId><artifactId>stop-test</artifactId><version>1</version></project>");
        Path source=temp.resolve("src/main/java/Example.java");Files.createDirectories(source.getParent());
        Files.writeString(source,"public class Example { public int value() { return 1; } }");
        Path script=temp.resolve(Proc.isWindows()?"fake-agent.cmd":"fake-agent.sh");
        String commands=Proc.isWindows()
            ? "@echo off\r\necho ready > \"%~dp0started\"\r\n:wait\r\nif exist \"%~dp0release\" goto done\r\nping -n 2 127.0.0.1 >nul\r\ngoto wait\r\n:done\r\necho OK\r\n"
            : "#!/bin/sh\ndir=$(dirname \"$0\")\nprintf ready > \"$dir/started\"\nwhile [ ! -f \"$dir/release\" ]; do sleep 0.1; done\nprintf 'OK\\n'\n";
        Files.writeString(script,commands);
        if(!Proc.isWindows())assertTrue(script.toFile().setExecutable(true));
        ProjectConfig c=ConfigFactory.createDefaultConfig(temp.resolve("pom.xml").toString());
        c.selectedModulePaths=java.util.List.of(".");c.agent.enabled=true;c.agent.executable=script.toString();
        return c;
    }

    private static JsonObject input(String mode) {JsonObject in=new JsonObject();in.addProperty("mode",mode);return in;}
    private static void waitUntil(BooleanSupplier condition) throws Exception {
        long end=System.nanoTime()+Duration.ofSeconds(8).toNanos();
        while(!condition.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(25);
        assertTrue(condition.getAsBoolean(),()->"condition did not become true\n"+Thread.getAllStackTraces().entrySet().stream()
                .filter(e->e.getKey().getName().startsWith("pool-")||e.getKey().getName().startsWith("Thread-"))
                .map(e->e.getKey().getName()+" "+e.getKey().getState()+" "+java.util.Arrays.toString(e.getValue()))
                .collect(java.util.stream.Collectors.joining("\n")));
    }
    private static Map<String,Object> state(DesktopEngine engine){return engine.snapshot(0);}

    @Test void gracefulRequestKeepsProbeAliveAndFinishesAsCancelledAfterItsResponse() throws Exception {
        ProjectConfig c=project();Path db=temp.resolve("workspace.db");
        try(DesktopEngine engine=new DesktopEngine(db)){
            JsonObject start=input("loop");start.add("config",JsonParser.parseString(Json.toJson(c)));
            engine.request("/run/start",start);waitUntil(()->Files.exists(temp.resolve("started")));
            engine.request("/run/stop",input("after-round"));
            assertEquals("running",state(engine).get("status"));
            assertEquals(true,state(engine).get("stopAfterRoundRequested"));
            assertNull(state(engine).get("finishedAt"));
            assertThrows(IllegalStateException.class,()->engine.request("/config/save",start));
            Files.writeString(temp.resolve("release"),"continue");
            waitUntil(()->state(engine).get("finishedAt")!=null);
            assertEquals("cancelled",state(engine).get("status"));
            assertTrue(((java.util.List<?>)state(engine).get("rounds")).isEmpty());
            assertEquals(CoverageLoopStopReason.after_round,((CoverageLoopResult)state(engine).get("loop")).stopReason);
        }
    }

    @Test void gracefulRequestCanBeEscalatedToImmediateStop() throws Exception {
        ProjectConfig c=project();
        try(DesktopEngine engine=new DesktopEngine(temp.resolve("workspace.db"))){
            JsonObject start=input("loop");start.add("config",JsonParser.parseString(Json.toJson(c)));
            engine.request("/run/start",start);waitUntil(()->Files.exists(temp.resolve("started")));
            engine.request("/run/stop",input("after-round"));
            engine.request("/run/stop",input("immediate"));
            waitUntil(()->state(engine).get("finishedAt")!=null);
            assertEquals("cancelled",state(engine).get("status"));assertFalse(Files.exists(temp.resolve("release")));
            assertTrue(((java.util.List<?>)state(engine).get("rounds")).isEmpty());
        }
    }

    @Test void actualAgentDeadlinePreservesThreePartialTestFilesAndRecordsTimeout() throws Exception {
        ProjectConfig c=project();Path tests=temp.resolve("src/test/java");Files.createDirectories(tests);
        Path script=Path.of(c.agent.executable);String commands=Files.readString(script);
        StringBuilder writes=new StringBuilder();
        for(String name:java.util.List.of("AlphaTest","BetaTest","GammaTest")) {
            Path file=tests.resolve(name+".java");
            writes.append(Proc.isWindows()?"echo class "+name+" {} > \""+file+"\"\r\n"
                    :"printf 'class "+name+" {}' > \""+file+"\"\n");
        }
        commands=commands.replace(Proc.isWindows()?":wait":"while [",writes+(Proc.isWindows()?":wait":"while ["));
        Files.writeString(script,commands);
        com.coverageloop.service.MavenRunner.OutputSink sink=new com.coverageloop.service.MavenRunner.OutputSink(){
            public void output(MavenOutputEvent e){} public void progress(MavenProgressEvent e){}
        };
        com.coverageloop.service.AgentRunner runner=new com.coverageloop.service.AgentRunner(sink){
            @Override protected long roundTimeoutMillis(AgentOptions options){return 1500;}
        };
        MavenRunResult baseline=new MavenRunResult();baseline.round=1;baseline.runId="timeout-run";
        baseline.runDirectory=temp.resolve("run").toString();Files.createDirectories(Path.of(baseline.runDirectory));
        baseline.logPath=temp.resolve("maven.log").toString();baseline.coverageSnapshotPath=temp.resolve("coverage.json").toString();
        baseline.command=new MavenCommandPreview();baseline.command.executable="mvn";
        try {
            AgentRoundResult result=runner.runRound(c,baseline,"coverage");
            assertEquals(AgentExecutionStatus.timed_out,result.status);assertEquals("round-timeout",result.failureKind);
            assertEquals(3,result.changedTestFiles.size());assertFalse(result.completionMarkerSeen);
            assertTrue(Files.readString(Path.of(result.logPath)).contains("[PROCESS_EXITED] status=timed_out"));
            assertTrue(Files.exists(tests.resolve("GammaTest.java")));assertFalse(runner.isRunning());
        } finally {runner.stop();}
    }
}
