package com.coverageloop.model;

import java.util.ArrayList;
import java.util.List;

/** 单个模块的源码扫描结果 */
public class ModuleSources {
    public String modulePath;
    public List<String> packages = new ArrayList<>();
    public List<JavaClassInfo> classes = new ArrayList<>();
    public List<JavaClassInfo> testClasses = new ArrayList<>();
    public int sourceFileCount;
    public int testFileCount;
}
