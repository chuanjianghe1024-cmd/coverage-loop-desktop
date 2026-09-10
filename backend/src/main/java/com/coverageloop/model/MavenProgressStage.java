package com.coverageloop.model;

import com.google.gson.annotations.SerializedName;

/** Maven 进度阶段 */
public enum MavenProgressStage {
    @SerializedName("agent-probing")
    agent_probing,
    @SerializedName("agent-running")
    agent_running,
    @SerializedName("preparing")
    preparing,
    @SerializedName("resolving")
    resolving,
    @SerializedName("installing")
    installing,
    @SerializedName("compiling")
    compiling,
    @SerializedName("testing")
    testing,
    @SerializedName("reporting")
    reporting,
    @SerializedName("summarizing")
    summarizing,
    @SerializedName("completed")
    completed,
    @SerializedName("failed")
    failed;

    public static MavenProgressStage parse(String value) {
        if (value == null) return preparing;
        try {
            return valueOf(value);
        } catch (IllegalArgumentException error) {
            return preparing;
        }
    }
}
