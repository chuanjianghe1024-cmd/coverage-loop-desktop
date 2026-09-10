package com.coverageloop.model;

/** Maven 进度事件 */
public class MavenProgressEvent {
    public String runId;
    public int round;
    public MavenProgressStage stage;
    public int percent;
    public String message;
    public String timestamp;
    public boolean indeterminate;
    public java.util.List<com.coverageloop.service.BuildProgress.ModuleState> modules = java.util.List.of();
}
