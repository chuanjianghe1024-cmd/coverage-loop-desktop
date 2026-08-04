package com.coverageloop.model;

/** Maven / Agent 输出事件 */
public class MavenOutputEvent {
    /** "stdout" | "stderr" | "system" */
    public String stream;
    public String text;
    public String timestamp;
    public String runId;
    public Integer round;
}
