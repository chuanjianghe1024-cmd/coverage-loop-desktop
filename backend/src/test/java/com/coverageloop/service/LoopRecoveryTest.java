package com.coverageloop.service;

import com.coverageloop.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class LoopRecoveryTest {
    @TempDir Path temp;
    static final MavenRunner.OutputSink SINK = new MavenRunner.OutputSink() {
        public void output(MavenOutputEvent event) {}
        public void progress(MavenProgressEvent event) {}
    };

    static MavenRunResult measured(int number, boolean reached) {
        MavenRunResult r = new MavenRunResult(); r.round=number; r.runId="run-1"; r.exitCode=0;
        r.tests.status=TestExecutionStatus.passed;
        CoverageModuleResult module = new CoverageModuleResult(); module.source="jacoco"; module.classCount=1;
        r.coverage.add(module);
        if (!reached) r.groups.pending.add(new CoverageGroupedClass());
        return r;
    }
    static MavenRunResult failed(int number, String kind) {
        MavenRunResult r=measured(number,false);r.exitCode=1;r.tests.status=TestExecutionStatus.build_failed;
        r.tests.failureKind=kind;r.tests.message="same error";return r;
    }
    static AgentRoundResult attempt(AgentExecutionStatus status) {
        AgentRoundResult a=new AgentRoundResult();a.status=status;
        a.failureKind=status==AgentExecutionStatus.timed_out?"round-timeout":status==AgentExecutionStatus.failed?"api-transient":null;
        a.changedTestFiles=List.of("A.java","B.java","C.java");return a;
    }

    static class Harness {
        final ProjectConfig config=new ProjectConfig();
        final List<Long> waits=new ArrayList<>();
        final List<MavenRunner.RunOptions> options=new ArrayList<>();
        final List<CoverageLoopRunner.RecoveryNotice> notices=new ArrayList<>();
        final Deque<MavenRunResult> validations=new ArrayDeque<>();
        final Deque<AgentRoundResult> attempts=new ArrayDeque<>();
        final Deque<AgentExecutionStatus> probes=new ArrayDeque<>();
        int probeCalls,agentCalls,mavenCalls,agentStops,mavenStops;
        Consumer<Integer> duringAgent=n->{},duringMaven=n->{},duringProbe=n->{};
        final MavenRunner maven=new MavenRunner(new MavenRunner.RunnerPaths(),SINK) {
            @Override public MavenRunResult run(ProjectConfig c,String session,RunOptions opts) {
                options.add(opts);duringMaven.accept(++mavenCalls);
                assertFalse(validations.isEmpty(),"unexpected extra Maven round");
                MavenRunResult next=validations.removeFirst();
                if(mavenCalls>1)assertEquals("run-1",session);
                return next;
            }
            @Override public boolean stop(){mavenStops++;return true;}
        };
        final AgentRunner agent=new AgentRunner(SINK) {
            @Override public AgentProbeResult probe(ProjectConfig c) {
                duringProbe.accept(++probeCalls);
                AgentProbeResult p=new AgentProbeResult();p.status=probes.isEmpty()?AgentExecutionStatus.passed:probes.removeFirst();
                p.failureKind=p.status==AgentExecutionStatus.passed?null:"api-transient";return p;
            }
            @Override public AgentRoundResult runRound(ProjectConfig c,MavenRunResult previous,String mode) {
                duringAgent.accept(++agentCalls);assertFalse(attempts.isEmpty(),"unexpected extra Agent");
                AgentRoundResult a=attempts.removeFirst();a.round=previous.round;a.mode=mode;return a;
            }
            @Override public boolean stop(){agentStops++;return true;}
        };
        CoverageLoopRunner loop;
        Harness() {
            config.agent.enabled=true;config.selectedModulePaths=List.of("module-a");
            loop=new CoverageLoopRunner(maven,agent,r->{},a->{},notices::add) {
                @Override protected boolean awaitRetry(long ms){waits.add(ms);return true;}
            };
        }
        CoverageLoopResult run() throws Exception {return loop.run(config);}
    }

    @Test void timeoutWithThreeChangedFilesStartsFreshValidationAndCanReachTargetWithoutAnotherAgent() throws Exception {
        Harness h=new Harness();h.validations.addAll(List.of(measured(1,false),measured(2,true)));
        h.attempts.add(attempt(AgentExecutionStatus.timed_out));
        CoverageLoopResult result=h.run();
        assertEquals(CoverageLoopStopReason.target_reached,result.stopReason);
        assertEquals(2,result.rounds.size());assertEquals(1,h.agentCalls);
        assertEquals(3,result.agentRounds.get(0).changedTestFiles.size());
        assertFalse(result.agentRounds.get(0).completionMarkerSeen);assertTrue(h.waits.isEmpty());
        assertEquals("round-timeout",result.agentRounds.get(0).failureKind);
    }

    @Test void transientFailuresBackOffAndSuccessfulAgentResetsTheNextDelay() throws Exception {
        Harness h=new Harness();for(int n=1;n<=6;n++)h.validations.add(measured(n,n==6));
        h.attempts.addAll(List.of(attempt(AgentExecutionStatus.failed),attempt(AgentExecutionStatus.failed),
                attempt(AgentExecutionStatus.passed),attempt(AgentExecutionStatus.failed),attempt(AgentExecutionStatus.passed)));
        assertEquals(CoverageLoopStopReason.target_reached,h.run().stopReason);
        assertEquals(List.of(60000L,120000L,60000L),h.waits);
        assertEquals(5,h.agentCalls);assertEquals(6,h.mavenCalls);
        assertNotNull(h.notices.stream().filter(n->n.retryAt()!=null).findFirst().orElseThrow().retryAt());
    }

    @Test void persistentModeContinuesPastRoundAndRepeatedFailureLimits() throws Exception {
        Harness h=new Harness();h.config.agent.maxRounds=1;h.config.agent.maxSameFailures=1;
        h.validations.addAll(List.of(failed(1,"build"),failed(2,"build"),measured(3,true)));
        h.attempts.addAll(List.of(attempt(AgentExecutionStatus.passed),attempt(AgentExecutionStatus.passed)));
        CoverageLoopResult result=h.run();
        assertEquals(CoverageLoopStopReason.target_reached,result.stopReason);
        assertTrue(result.agentRounds.stream().allMatch(a->"repair".equals(a.mode)));
        assertEquals(List.of(60000L,120000L),h.waits);
    }

    @Test void probeTimeoutIsRetriedBeforeAnyMavenOrAgentWork() throws Exception {
        Harness h=new Harness();h.probes.addAll(List.of(AgentExecutionStatus.timed_out,AgentExecutionStatus.passed));
        h.validations.add(measured(1,true));
        assertEquals(CoverageLoopStopReason.target_reached,h.run().stopReason);
        assertEquals(2,h.probeCalls);assertEquals(0,h.agentCalls);assertEquals(List.of(60000L),h.waits);
    }

    @Test void dependencyFailureWaitsForRebuildAndRetriesFailedInstallWithoutAgentEdits() throws Exception {
        Harness h=new Harness();MavenRunResult broken=failed(1,"dependency-resolution");
        broken.preInstall.attempted=true;broken.preInstall.exitCode=1;
        h.validations.addAll(List.of(broken,measured(2,true)));
        assertEquals(CoverageLoopStopReason.target_reached,h.run().stopReason);
        assertEquals(0,h.agentCalls);assertTrue(h.options.get(1).forcePreInstall);
        assertEquals(List.of(60000L),h.waits);
    }

    @Test void invalidReportIsRetriedAndCannotSatisfyGate() throws Exception {
        Harness h=new Harness();MavenRunResult missing=measured(1,true);missing.coverage.clear();
        h.validations.addAll(List.of(missing,measured(2,true)));
        assertEquals(CoverageLoopStopReason.target_reached,h.run().stopReason);
        assertEquals(2,h.mavenCalls);assertEquals(0,h.agentCalls);assertEquals(List.of(60000L),h.waits);
    }

    @Test void gracefulStopDuringTimedOutAgentStillVerifiesItsPartialWork() throws Exception {
        Harness h=new Harness();h.validations.addAll(List.of(measured(1,false),measured(2,false)));
        h.attempts.add(attempt(AgentExecutionStatus.timed_out));h.duringAgent=n->h.loop.stopAfterRound();
        CoverageLoopResult result=h.run();
        assertEquals(CoverageLoopStopReason.after_round,result.stopReason);assertEquals(2,h.mavenCalls);
        assertEquals(0,h.agentStops);assertEquals(0,h.mavenStops);assertTrue(h.waits.isEmpty());
    }

    @Test void immediateStopDuringAgentDoesNotStartAnotherVerification() throws Exception {
        Harness h=new Harness();h.validations.add(measured(1,false));h.attempts.add(attempt(AgentExecutionStatus.aborted));
        h.duringAgent=n->h.loop.stop();
        assertEquals(CoverageLoopStopReason.aborted,h.run().stopReason);
        assertEquals(1,h.mavenCalls);assertEquals(1,h.agentStops);assertEquals(1,h.mavenStops);
    }

    @Test void gracefulStopDuringBaselineStopsBeforeStartingAgent() throws Exception {
        Harness h=new Harness();h.validations.add(measured(1,false));h.duringMaven=n->h.loop.stopAfterRound();
        assertEquals(CoverageLoopStopReason.after_round,h.run().stopReason);assertEquals(0,h.agentCalls);
    }

    @Test void queuedStopIsNotResetByWorkerStartup() throws Exception {
        Harness h=new Harness();h.loop.stop();
        assertEquals(CoverageLoopStopReason.aborted,h.run().stopReason);assertEquals(0,h.probeCalls);
    }

    @Test void bothStopModesWakeLongBackoffImmediately() throws Exception {
        for(boolean immediate:List.of(false,true)) {
            Harness h=new Harness();h.probes.add(AgentExecutionStatus.timed_out);
            CountDownLatch waiting=new CountDownLatch(1);
            h.loop=new CoverageLoopRunner(h.maven,h.agent) {
                @Override protected boolean awaitRetry(long ms) throws InterruptedException {
                    waiting.countDown();return super.awaitRetry(ms);
                }
            };
            ExecutorService worker=Executors.newSingleThreadExecutor();
            try {
                Future<CoverageLoopResult> future=worker.submit(h::run);
                assertTrue(waiting.await(2,TimeUnit.SECONDS));
                if(immediate)h.loop.stop();else h.loop.stopAfterRound();
                assertEquals(immediate?CoverageLoopStopReason.aborted:CoverageLoopStopReason.after_round,
                        future.get(2,TimeUnit.SECONDS).stopReason);
                assertEquals(1,h.probeCalls);assertEquals(0,h.mavenCalls);
            } finally {h.loop.stop();worker.shutdownNow();}
        }
    }

    @Test void retryScheduleCapsAndSuccessfulSessionsDoNotInheritTransientErrors() {
        AgentOptions o=new AgentOptions();
        assertEquals(List.of(60,120,240,480,600,600),
                java.util.stream.IntStream.rangeClosed(1,6).map(n->CoverageLoopRunner.retryDelaySeconds(o,n)).boxed().toList());
        for(String error:List.of("APITimeoutError: request timed out","HTTP 503","Connection reset by peer","ReadTimeout")) {
            assertEquals("api-transient",AgentFailure.classify(AgentExecutionStatus.failed,null,error));
            assertNull(AgentFailure.classify(AgentExecutionStatus.passed,null,error+"\nBATCH_COMPLETE"));
        }
        assertEquals("agent-execution",AgentFailure.classify(AgentExecutionStatus.failed,"program not found",""));
    }

    @Test void oldConfigurationsDefaultToPersistentModeAndExplicitOptOutSurvivesCopyAndSave() {
        String root=temp.resolve("pom.xml").toString();
        ProjectConfig old=ConfigStore.parseConfig("{\"schemaVersion\":5,\"agent\":{\"enabled\":true,\"maxRounds\":5}}",root);
        assertTrue(old.agent.runUntilTarget);assertEquals(60,old.agent.retryDelaySeconds);assertEquals(600,old.agent.maxRetryDelaySeconds);
        old.agent.runUntilTarget=false;old.agent.retryDelaySeconds=90;old.agent.maxRetryDelaySeconds=900;
        String path=ConfigStore.saveProjectConfig(old);
        ProjectConfig restored=ConfigStore.parseConfig(com.coverageloop.util.Fs.readString(path),root);
        assertFalse(restored.agent.runUntilTarget);assertEquals(90,restored.agent.retryDelaySeconds);
        assertEquals(900,restored.copy().agent.maxRetryDelaySeconds);
    }

    @Test void unlimitedRunDoesNotOverwriteFourDigitRoundNumbers() throws Exception {
        ProjectConfig c=ConfigFactory.createDefaultConfig(temp.resolve("pom.xml").toString());
        RunHistory.PreparedRound first=RunHistory.prepareRound(c,null);
        Files.writeString(Path.of(first.runDirectory,"round-999-maven.log"),"old");
        Files.writeString(Path.of(first.runDirectory,"round-1000-maven.log"),"latest");
        assertEquals(1001,RunHistory.prepareRound(c,first.runId).round);
        assertEquals("latest",Files.readString(Path.of(first.runDirectory,"round-1000-maven.log")));
    }

    @Test void explicitBoundedModeStillHonorsRoundLimit() throws Exception {
        Harness h=new Harness();h.config.agent.runUntilTarget=false;h.config.agent.maxRounds=1;
        h.validations.add(measured(1,false));
        assertEquals(CoverageLoopStopReason.max_rounds,h.run().stopReason);assertEquals(0,h.agentCalls);
    }
}

