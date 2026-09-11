package com.coverageloop.service;

import com.coverageloop.model.AgentExecutionStatus;
import com.coverageloop.util.Text;
import java.util.regex.Pattern;

/** Classify failed CLI executions; a recovered error inside a successful session is not a failure. */
public final class AgentFailure {
    private AgentFailure() {}
    private static final Pattern TRANSIENT = Pattern.compile(
            "(?i)api.?timeout|read.?timeout|connect.?timeout|timed?\\s*out|timeout|超时|"
            + "api.?connection.?error|connection.?error|connection (?:reset|refused|aborted)|"
            + "econnreset|econnrefused|etimedout|socket hang up|remoteprotocolerror|"
            + "server disconnected|stream (?:closed|disconnected)|incomplete chunked read|"
            + "rate.?limit|too many requests|service unavailable|bad gateway|gateway timeout|"
            + "(?:http(?:/\\S+)?|status(?: code)?|error)[\\s:=\\[\\\"]+(?:408|429|500|502|503|504)\\b");

    public static String classify(AgentExecutionStatus status, String signal, String output) {
        if (status == AgentExecutionStatus.passed || status == AgentExecutionStatus.aborted) return null;
        if (status == AgentExecutionStatus.timed_out) return "round-timeout";
        String text = Text.stripAnsi((signal == null ? "" : signal) + "\n" + (output == null ? "" : output));
        if (text.length() > 32_000) text = text.substring(text.length() - 32_000);
        return TRANSIENT.matcher(text).find() ? "api-transient" : "agent-execution";
    }

    public static boolean isTransient(String kind) {
        return "round-timeout".equals(kind) || "api-transient".equals(kind);
    }

    public static String description(String kind) {
        if ("round-timeout".equals(kind)) return "Agent 达到本轮时间上限";
        if ("api-transient".equals(kind)) return "API 超时、连接中断或服务暂不可用";
        return "Agent 执行未成功";
    }
}
