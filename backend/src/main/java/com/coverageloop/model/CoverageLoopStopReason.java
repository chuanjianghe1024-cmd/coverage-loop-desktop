package com.coverageloop.model;

/** 自动循环停止原因 */
public enum CoverageLoopStopReason {
    target_reached,
    invalid_report,
    max_rounds,
    maven_failed,
    agent_unavailable,
    agent_failed,
    repeated_maven_failure,
    after_round,
    aborted
}
