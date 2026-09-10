package com.coverageloop.model;

import com.google.gson.annotations.SerializedName;

/** Maven 测试执行状态 */
public enum TestExecutionStatus {
    @SerializedName("passed")
    passed,
    @SerializedName("no-tests")
    no_tests,
    @SerializedName("test-failed")
    test_failed,
    @SerializedName("build-failed")
    build_failed,
    @SerializedName("aborted")
    aborted;

    public static TestExecutionStatus parse(String value) {
        if (value == null) return aborted;
        switch (value) {
            case "passed": return passed;
            case "no-tests": return no_tests;
            case "test-failed": return test_failed;
            case "build-failed": return build_failed;
            default: return aborted;
        }
    }

    /** 与 TypeScript 端持久化的字符串一致 */
    public String json() {
        switch (this) {
            case passed: return "passed";
            case no_tests: return "no-tests";
            case test_failed: return "test-failed";
            case build_failed: return "build-failed";
            default: return "aborted";
        }
    }

    public String label() {
        switch (this) {
            case passed: return "测试通过";
            case no_tests: return "暂无测试";
            case test_failed: return "测试失败";
            case build_failed: return "构建失败";
            default: return "已终止";
        }
    }
}
