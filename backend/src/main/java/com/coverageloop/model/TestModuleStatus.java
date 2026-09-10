package com.coverageloop.model;

import com.google.gson.annotations.SerializedName;

/** 模块测试状态 */
public enum TestModuleStatus {
    @SerializedName("passed")
    passed,
    @SerializedName("no-tests")
    no_tests,
    @SerializedName("failed")
    failed,
    @SerializedName("unknown")
    unknown
}
