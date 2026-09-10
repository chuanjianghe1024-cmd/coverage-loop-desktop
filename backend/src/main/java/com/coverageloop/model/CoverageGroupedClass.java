package com.coverageloop.model;

import java.util.ArrayList;
import java.util.List;

/** 带初始/当前覆盖率的分组类 */
public class CoverageGroupedClass extends CoverageClassResult {
    public Double initialLineCoverage;
    public Double currentLineCoverage;

    public CoverageGroupedClass() {
    }

    public CoverageGroupedClass(CoverageClassResult source, Double initialLineCoverage, Double currentLineCoverage) {
        super(source.modulePath, source.packageName, source.className, source.qualifiedName,
                source.coveredLines, source.missedLines, source.lineCoverage);
        this.initialLineCoverage = initialLineCoverage;
        this.currentLineCoverage = currentLineCoverage;
    }
}
