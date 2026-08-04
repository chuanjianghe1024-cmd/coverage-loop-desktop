package com.coverageloop.model;

/** JaCoCo 覆盖率选项 */
public class CoverageOptions {
    public String jacocoVersion = "0.8.8";
    public int lineThreshold = 80;
    /** 分支覆盖率门槛，null 表示未启用 */
    public Integer branchThreshold = null;

    public CoverageOptions copy() {
        CoverageOptions copy = new CoverageOptions();
        copy.jacocoVersion = jacocoVersion;
        copy.lineThreshold = lineThreshold;
        copy.branchThreshold = branchThreshold;
        return copy;
    }
}
