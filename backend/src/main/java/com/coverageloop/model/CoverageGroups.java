package com.coverageloop.model;

import java.util.ArrayList;
import java.util.List;

/** 类级覆盖率分组：初始已满足 / 待补充 / 已补充 */
public class CoverageGroups {
    public int threshold;
    public String baselineCreatedAt;
    public List<CoverageGroupedClass> initialSatisfied = new ArrayList<>();
    public List<CoverageGroupedClass> pending = new ArrayList<>();
    public List<CoverageGroupedClass> supplemented = new ArrayList<>();
}
